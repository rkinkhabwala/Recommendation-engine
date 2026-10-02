# Real-time Recommendation System

A real-time recommender for **songs, videos, books and posts**: a user's actions change their recommendations within seconds. All four domains share one embedding space, so taste carries across domains. The system also includes:
- LLM-enriched metadata and cached "why this" explanations, generated asynchronously and never on the serving path.
- A learned ranker (LightGBM) with a training pipeline, model versioning and A/B testing.

- Design: [docs/architecture.md](docs/architecture.md). Plain-language overview: [architecture.md](architecture.md). Spec: [spec.md](spec.md).
- Conventions for contributors and AI sessions: [CLAUDE.md](CLAUDE.md).

```
client JSON ──► ingestion-api ──► Kafka (Avro) ──► stream-processor (Kafka Streams, EOS)
                                                     │ dedupe · user vectors · affinities · item stats
                                                     │ co-engagement · next-item · trending · attribution
                                                     ▼
catalog-service ──► catalog.items ──► embedding-worker ──► Qdrant      features.* ──► feature-writer ──► Redis
                                     (OpenAI or mock)                                (seq compare-and-set)
                                                                                         │
            GET /v1/recommendations ──► recommendation-api ◄── Redis + Qdrant + in-process caches
                                         candidates → rank → re-rank → fallback   (never calls OpenAI)
```

## Quick start (Docker only)

Requires Docker with about 8 GB of memory available. You don't need a JDK or an OpenAI key: embeddings come from a deterministic mock unless you configure OpenAI.

```bash
docker compose up -d --build            # infra + 5 services; the first build takes a few minutes
docker compose ps                       # wait until the services are running

# 1. Load the synthetic catalog (50k songs, 10k books, 10k videos, 20k posts). Items are embedded
#    and indexed; the enrichment-worker fills in missing moods, themes and topics (throttled).
docker compose --profile sim run --rm simulator catalog

# 2. Simulate listeners who follow next-track recommendations (~500 events/s).
docker compose --profile sim run --rm simulator simulate --users 5000 --rate 500 --duration 5m

# 3. Measure end-to-end freshness: time from a user's action to a changed recommendation list.
docker compose --profile sim run --rm simulator freshness --trials 5 --genre jazz --domain song
#    Cross-domain: rate jazz books, then read song recommendations.
docker compose --profile sim run --rm simulator freshness --domain book --read-domain song

# 4. Train, evaluate and A/B test the learned ranker (see "Learned ranking" below).
docker compose --profile ml run --rm ml recsys-export --out data
docker compose --profile ml run --rm ml recsys-train --domain song
```

To run everything in one go: `docker compose --profile sim run --rm simulator all`.

### Try the APIs

```bash
KEY=dev-key
# Send events. Clients send JSON; the ingestion API validates it and converts it to Avro.
curl -s -XPOST localhost:8081/v1/events -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' -d '{
  "events":[{"eventId":"01929a7e-0000-7000-8000-000000000001","userId":"me","itemId":"s_000042",
             "domain":"song","eventType":"play_end","value":200,"eventTs":"'$(date -u +%FT%TZ)'",
             "sessionId":"s1","context":{"device":"web","country":"US"},"media":{"durationMs":210000}}]}'

# Get recommendations. Each item has a reasonCode and a recommendationId for attribution.
curl -s "localhost:8080/v1/recommendations?userId=me&domain=song&context=next_track&seedItemId=s_000042&limit=10&country=US" \
  -H "X-Api-Key: $KEY" | jq

# Cold start: onboarding picks plus free text. The text is PII-scrubbed and embedded asynchronously.
curl -s -XPOST localhost:8081/v1/users/me/onboarding -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"picks":{"genres":["jazz","soul"]},"freeText":"late-night mellow jazz"}'

# Right to erasure: deletes the user's data across all stores (docs/architecture.md section 9).
curl -s -XDELETE localhost:8081/v1/users/me/data -H "X-Api-Key: $KEY"
```

### Learned ranking, model registry and A/B tests

```bash
# Export served lists (with sampled serving-time features), attributed outcomes and the catalog
# to Parquet. User ids are pseudonymized with HMAC (EXPORT_HMAC_SECRET).
docker compose --profile ml run --rm ml recsys-export --out data

# Train one LightGBM LambdaRank model per domain. Offline evaluation is on a later time slice.
# A model that beats the heuristic on position-free quality becomes `candidate` (served to A/B
# treatment variants); it is promoted to `current` by the IPS-NDCG gate or after an A/B win.
docker compose --profile ml run --rm ml recsys-train --domain song

# Analyse the experiment window: rates with 95% CIs, z-tests vs control, SRM check on users.
docker compose --profile ml run --rm ml recsys-ab-report --since 2026-10-02T18:48:00Z \
  --split song:control=0.5,song:lgbm_thompson=0.5

# Promote (or roll back) explicitly. The API hot-reloads within 30 s.
docker compose --profile ml run --rm ml recsys-promote --domain song --version v20261002T184710
```

Experiments are configured per domain under `recs.api.experiments` (recommendation-api `application.yml`). Each variant chooses its ranker (`heuristic`, `lightgbm` or `lightgbm:candidate`), its heuristic weights and its exploration strategy (`epsilon` or `thompson`). `GET /v1/experiments/assignment?userId=&domain=` shows a user's variant.

### Re-embedding (model, dimension or embedder change)

```bash
# 1. Build the new index + topic (Batch API with OpenAI; direct with the mock); switch the alias.
docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=backfill \
  -e RECS_INDEX_VERSION=items_mock_512_v3 -e RECS_BACKFILL_TARGET_INDEX=items_mock_512_v3 \
  -e RECS_BACKFILL_SWITCH_ALIAS=true embedding-worker
# 2. Point the stream processor, worker and API at the new space (user vectors rebuild from new events).
RECS_INDEX_VERSION=items_mock_512_v3 RECS_EMBEDDINGS_TOPIC=catalog.embeddings.items_mock_512_v3 \
  docker compose up -d stream-processor embedding-worker recommendation-api
```

Keep those two variables set (for example in `.env`) for later `docker compose up` runs. In production, step 2 is a shadow deployment that replays raw events first (docs/architecture.md §17).

### Use real OpenAI embeddings

```bash
export RECS_OPENAI_MODE=openai OPENAI_API_KEY=sk-...        # never commit keys
export RECS_INDEX_VERSION=items_te3s_512_v1                 # new collection; dimensions are encoded in the name
docker compose up -d embedding-worker stream-processor recommendation-api
```

LLM enrichment and explanations use `RECS_CHAT_MODEL` (default `gpt-5-mini`; verify the current model and its price first). The embedding model is `text-embedding-3-small`, requested with `dimensions=512`. Model, dimensions and prices are configuration values; see `RECS_EMBEDDING_MODEL` and `RECS_EMBEDDING_DIMS`. A new index version creates a new Qdrant collection. To re-embed everything, replay the compacted catalog topic:

```bash
docker compose stop embedding-worker
docker compose exec kafka kafka-consumer-groups --bootstrap-server kafka:29092 --group catalog-embedder \
  --topic catalog.items.v1 --reset-offsets --to-earliest --execute
docker compose start embedding-worker
```

Cost and budget: `recs_openai_cost_usd_total` and `recs_openai_budget_used_ratio` are exported as metrics. Prometheus alerts fire at 50% and 80% of the $50/month dev budget. At 100%, non-essential jobs pause.

## Observability

| What | Where |
|---|---|
| Prometheus | http://localhost:9090. Alerts are in `config/prometheus/alerts.yml` |
| Grafana | http://localhost:3000 (datasource provisioned; dashboards are a Phase 3 item) |
| Serving latency | `histogram_quantile(0.99, sum by (le) (rate(recs_api_request_seconds_bucket[1m])))` |
| Freshness (event → Redis) | `histogram_quantile(0.95, sum by (le) (rate(recs_feature_freshness_seconds_bucket{feature="user_short_term"}[1m])))` |
| Degradation | `sum by (level) (rate(recs_api_fallback_total[1m]))` |
| Online CTR per variant | `sum by (variant) (rate(recs_online_outcomes_total{outcome="PLAYED"}[5m])) / sum by (variant) (rate(recs_online_served_items_total[5m]))` |
| Cache hit rate | `cache_gets_total{cache="item_meta"}` by `result` |

Logs are structured JSON (ECS format) with trace ids. Set `LOG_FORMAT=` for plain text.

## Development

```bash
./gradlew build              # compile + Spotless check + unit tests (no Docker needed)
./gradlew integrationTest    # Testcontainers tests (*IT): Redis, Qdrant, Postgres. Needs Docker.
./gradlew spotlessApply      # format (google-java-format)
./gradlew :services:recommendation-api:bootRun   # run one service against `docker compose up kafka redis ...`
```

- Gradle downloads JDK 21 automatically (toolchains). The daemon also runs on JDK 21; see `gradle/gradle-daemon-jvm.properties`.
- **iCloud-synced folders** such as `~/Desktop` and `~/Documents` create "file 2" conflict copies of rapidly rewritten files, which breaks builds. Either keep the checkout elsewhere, or add `recsys.buildDirName=build.nosync` to `~/.gradle/gradle.properties`; iCloud skips `*.nosync` directories.
- Load tests: `load-tests/*.js` (k6), with the SLOs as thresholds:
  `docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load-tests/recommendations.js`

### Layout

| Path | What |
|---|---|
| `libs/event-schema` | Avro contracts and the BACKWARD-compatibility test against released snapshots |
| `libs/common` | IDs (UUIDv7), deadlines, vector math, order-independent decayed accumulators |
| `libs/feature-store` | Redis key schema, feature models, Lua compare-and-set writer, pipelined reader |
| `libs/vector-index` | `VectorIndex` interface; Qdrant implementation (aliases, filters) |
| `libs/openai-client` | Embeddings, structured-output chat and Batch API clients (retry, circuit breaker, rate limit, cost, budget); mocks; PII scrubber |
| `libs/web-support` | API-key filter and error mapping |
| `services/ingestion-api` | `POST /v1/events`, onboarding, deletion |
| `services/catalog-service` | Catalog upserts → Postgres → Kafka + Redis metadata, with a reconciler |
| `services/stream-processor` | Kafka Streams feature topology (per-domain and cross-domain) and the Redis feature-writer |
| `services/embedding-worker` | Catalog → embeddings → Qdrant; onboarding seed vectors; backfill / re-embed job (Batch API) |
| `services/enrichment-worker` | LLM metadata enrichment (structured outputs) and cached "why this" explanations |
| `ml/` | Python (uv): export, dataset, LightGBM training, offline metrics, registry, A/B report, embedding-dims benchmark |
| `services/recommendation-api` | `GET /v1/recommendations` for all domains; heuristic and LightGBM rankers (pure-Java evaluator), A/B experiments |
| `tools/event-simulator` | Synthetic catalog, listener simulation, freshness check |
| `config/` | Kafka topics, Prometheus scrape config and alerts, Grafana |

## Operations

- **Rebuild Redis** (it is a materialized view): flush Redis, then reset the `feature-writer` consumer group to earliest on the `features.*` topics.
- **User deletion:** call `DELETE /v1/users/{id}/data`. The stream processor deletes the user's state and emits tombstones. The writer deletes the user's Redis keys and sets a 30-day deletion marker so replayed records can't recreate them. Raw topics expire in ≤ 7 days.
- **Late events:** events older than 24 hours relative to stream time go to `events.late.v1`, which is used offline only. Trending windows accept events up to 10 minutes late.

## Local verification (Phase 1)

Measured on a laptop with the whole stack in Docker (8 GB, 10 CPUs), a 50k-song catalog, mock embeddings, and simulated listeners at ~370 events/s, with k6 sending 100 req/s at the same time:

| SLO | Target | Measured |
|---|---|---|
| Serving latency, client side (k6) | p50 < 30 ms, p99 < 100 ms | p50 4.6 ms, p99 40 ms |
| Serving latency, server side | — | p50 8.7 ms, p99 38 ms |
| Event → features in Redis | < 5 s | p50 0.31 s, p95 0.70 s, p99 2.2 s |
| Action → changed recommendations (`simulator freshness`) | < 5 s | median 0.33 s, max 0.59 s |
| Degraded responses | < 2 % | 0.42 % |

Simulated listeners who follow the recommendations complete 69% of tracks and skip 14% early. With popular-only fallback lists the figures were 35% and 50%.

Outage drills:
- **Qdrant down:** responses are `PARTIAL` and served from collaborative-filtering and trending sources.
- **Redis down:** responses are `CACHED_POPULAR`/`STATIC`, still 200, in about 10 ms.
- **Redis restarted empty:** Redis is rebuilt automatically from the compacted topics.
- **User deletion:** all of the user's keys are gone within about 1 s, and later writes are refused.

## Local verification (Phase 2)

All four domains ran together: a 90k-item catalog (50k songs, 10k books, 10k videos, 20k posts), all of it LLM-enriched (with the mock LLM) and re-embedded through the backfill job into `items_mock_512_v2`. About 390 events/s of simulated multi-domain traffic ran alongside k6 sending 100–200 req/s across the domains.

| Check | Target | Measured |
|---|---|---|
| Serving latency, k6 mixed domains at 200 req/s (idle) | p50 < 30 ms, p99 < 100 ms | p50 2.4 ms, p99 9.6 ms |
| Serving latency under simulation load (server side) | p50 < 30 ms, p99 < 100 ms | p50 4.8 ms, p99 33 ms |
| Event → features in Redis (`user_short_term`) | < 5 s | p50 0.32 s, p95 0.53 s, p99 0.72 s |
| Event → cross-domain vector | < 5 s | p95 1.3 s |
| Action → ≥ 50% on-genre recommendations, same domain (song/video/book/post) | < 5 s | max 0.5–1.3 s |
| Action → ≥ 50% on-genre recommendations, cross-domain (book→song, song→book, post→video) | < 5 s | max 0.9–1.2 s |
| Degraded responses (steady state) | < 2 % | 0.4 % |

Learned ranker (LightGBM LambdaRank, trained on about 500k exported served items):
- **Offline:** the song model separates good from poor engagements better than the heuristic (quality AUC 0.772 vs 0.725), so it became the A/B `candidate`. The book model also passed (0.568 vs 0.541). The post model passed the IPS gate and was promoted to `current`. The video model scored below the heuristic (0.651 vs 0.665) and was correctly refused, so video's treatment variant keeps serving the heuristic.
- **A/B on songs** (control = heuristic + ε-greedy, treatment = LightGBM + Thompson; ~700 users per arm; SRM p = 0.18):
  - Completion: +4.6 points (95% CI +3.1 to +6.1, p = 3e-9).
  - Early-skip rate: −4.4 points (p = 1e-14).
  - CTR: unchanged.
  - The song model was then promoted to `current`.
- **Video A/A check:** both arms served the heuristic, and the report showed no significant difference (p = 0.07). Analysing outside the experiment window, by contrast, produced a spurious "+6.7 points" that the SRM check flagged.
- **Caveat:** these CIs are per impression, not user-clustered, so they are optimistic. The simulator's users also respond to a known taste model, so these numbers validate the pipeline, not real-world lift.

## Phase 1 scope and known limits

These are done:
- Ingestion with dedupe on `event_id`.
- Real-time user and item features.
- Five candidate sources: semantic ANN, new items, item-item co-engagement, next-item, and trending/popular-in-region.
- Heuristic ranking.
- Re-ranking: hard filters, diversity, freshness boost, familiarity cap, ε-greedy exploration with logged propensities.
- A five-level fallback ladder.
- Served/attributed logging with online metrics.
- A/B bucketing.
- Cold start for new users and new items.

Known limits of the MVP:
- Catalog publishing is synchronous per item. That's fine for API batches, but a large reconciler backlog drains at only about 75 items/s.
- The OpenAI budget guard counts spend per process.
- After deletion, a user id stays blocked for 30 days by its deletion marker.
- Throttled emissions (long-term vectors, item stats, neighbours) keep their "dirty" sets in memory, so a restart delays them until the next event for that key.
- Local infra is single-node (replication factor 1).

Phase 2 added:
- Books, videos and posts, with per-domain signal weights, consumed windows, re-rank rules, templates and fallbacks.
- A cross-domain taste vector.
- LLM metadata enrichment and cached explanations.
- A learned LightGBM ranker with export, training, offline metrics, two-stage gating, a versioned registry with hot reload, and a pure-Java evaluator.
- A per-domain A/B framework with an analysis report.
- Thompson-sampling exploration.
- Batch API backfills and an index-switch runbook.
- An embedding-dimension benchmark script, which needs `OPENAI_API_KEY` and has not been run.

Phase 2 known limits:
- Enrichment re-embeds every item it touches, so rollouts are throttled (`RECS_ENRICHMENT_MAX_PER_SECOND`). Large rollouts should use the backfill path.
- On single-click surfaces, offline replay can't validate a new ranking. That's why the gate is two-stage and the A/B test decides.
- A/B CIs are not user-clustered. Explanations cover only sampled lists (20%).
- In this local stack, the stream-processor was switched to the new index directly, not through a shadow deployment, so user vectors started over.

Deferred to phase 3 (marked `TODO(phase-3)` in code):
- JWT auth, a transactional outbox and shared budget accounting.
- Dashboards, Kubernetes and autoscaling.
- An object-store model registry with approvals, and scheduled retraining.
- IPS/SNIPS estimators, user-clustered A/B statistics with CUPED and sequential tests, and per-user HMAC keys for export.
- A Kafka Connect S3 sink and LLM re-ranking for email digests.
