# Real-time Recommendation System

A song recommendation system that updates as listeners act: a user's plays, skips and likes change their recommendations within seconds. Phase 1 covers songs; books, videos and posts follow in Phase 2.

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

# 1. Load the synthetic catalog (50k songs). The embedding-worker embeds and indexes them.
docker compose --profile sim run --rm simulator catalog

# 2. Simulate listeners who follow next-track recommendations (~500 events/s).
docker compose --profile sim run --rm simulator simulate --users 5000 --rate 500 --duration 5m

# 3. Measure end-to-end freshness: time from a user's action to a changed recommendation list.
docker compose --profile sim run --rm simulator freshness --trials 5 --genre jazz
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

### Use real OpenAI embeddings

```bash
export RECS_OPENAI_MODE=openai OPENAI_API_KEY=sk-...        # never commit keys
export RECS_INDEX_VERSION=items_te3s_512_v1                 # new collection; dimensions are encoded in the name
docker compose up -d embedding-worker stream-processor recommendation-api
```

The model is `text-embedding-3-small`, requested with `dimensions=512`. Model, dimensions and prices are configuration values; see `RECS_EMBEDDING_MODEL` and `RECS_EMBEDDING_DIMS`. A new index version creates a new Qdrant collection. To re-embed everything, replay the compacted catalog topic:

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
| `libs/openai-client` | `EmbeddingClient`; OpenAI client (retry, circuit breaker, rate limit, cost, budget); mock; PII scrubber |
| `libs/web-support` | API-key filter and error mapping |
| `services/ingestion-api` | `POST /v1/events`, onboarding, deletion |
| `services/catalog-service` | Catalog upserts → Postgres → Kafka + Redis metadata, with a reconciler |
| `services/stream-processor` | Kafka Streams feature topology and the Redis feature-writer |
| `services/embedding-worker` | Catalog → embeddings → Qdrant; onboarding seed vectors |
| `services/recommendation-api` | `GET /v1/recommendations` |
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

These are deferred (marked `TODO(phase-N)` in code):
- Other domains, LLM enrichment and explanations, a learned ranker, the S3 training sink, Batch API backfills, Thompson sampling (phase 2).
- JWT auth, a transactional outbox, shared budget accounting, dashboards, Kubernetes (phase 3).
