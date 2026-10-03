# CLAUDE.md

Real-time recommendation system for songs, books, videos and posts. Full spec: `spec.md`.
Technical design: `docs/architecture.md`. (`architecture.md` at the root is a plain-language explainer, not the design.)

## Current phase
Phases 1 (songs MVP), 2 (multi-domain, LLM enrichment/explanations, learned ranker, A/B,
cross-domain) and 3 (production hardening: JWT auth, catalog outbox, shared OpenAI budget, load
shedding, tracing, dashboards, alerting, Kubernetes, load tests, cost report) are implemented. Phase 3 is
awaiting review. Pause for review after each phase. Mark deferred work as `// TODO(phase-4): ...`.

## Hard rules
1. **OpenAI is never called in the serving path.** `services/recommendation-api` must not depend on
   `libs/openai-client` (enforced by an ArchUnit test and a Gradle dependency check). The hot path reads only
   precomputed data from Redis, Qdrant, in-process caches and model files. LLM work (enrichment,
   explanations) runs only in `enrichment-worker`; serving reads explanation caches read-only.
2. **All external writes are idempotent.** Kafka Streams exactly-once covers Kafka→Kafka only. Writes to
   Redis/Qdrant/Postgres are keyed upserts carrying a monotonic `seq` (or version); the writer applies a
   compare-and-set (Redis Lua script) so a replayed record can never regress state. Never use `INCR`/`ZINCRBY`
   on serving state; aggregate inside Kafka Streams and write absolute values.
3. **Model names, embedding dimension and prices are config, never literals.** The index version encodes
   model + dims + template version (e.g. `items_te3s_512_v1`). Serving reads via the alias `items_current`.
4. **No PII to OpenAI.** Send item content only. Free text supplied by users (onboarding) is scrubbed first and sent without identifiers.
5. **No PII in events beyond `user_id`.** No raw search queries or free text in event topics or logs.
6. **Every network call has a timeout.** Serving calls have per-stage deadlines and degrade instead of failing.
7. **Contract topics are Avro, BACKWARD compatible**, registered in Schema Registry. Breaking change → new
   topic `*.v2`, and add a snapshot under `libs/event-schema/src/test/resources/schema-snapshots/`.
   Clients send JSON over HTTP; only the ingestion API converts to Avro. Internal topics owned by one
   service (`features.*`, repartition topics, changelogs) use JSON (`FeatureEnvelope`, `JsonSerde`).
   Call `AvroTrust.install()` in any process that deserializes Avro.
8. **Secrets** come from env vars (`OPENAI_API_KEY`) or a secrets manager, never committed. The default
   `recs.openai.mode=mock` means local runs and tests need no key.
9. **Redis is a rebuildable view:** only the feature-writer writes it, from compacted `features.*`
   topics (workers publish features to Kafka, never to Redis). User keys are per domain:
   `u:{id}:st|lt|seed:<domain>` plus `u:{id}:x` (cross-domain).
10. **Training/serving parity:** both rankers use `FeatureExtractor`; changing `FeatureExtractor.FEATURES`
   requires the same change in `ml/recsys_ml/__init__.py` and retraining (the registry refuses mismatches).
11. **Embedding-space changes need a new index version** (model, dims, template or mock embedder):
   backfill → new collection + `catalog.embeddings.<index>` topic → switch `RECS_INDEX_VERSION` and
   `RECS_EMBEDDINGS_TOPIC` everywhere (see README "Re-embedding"). The local `.env` holds the current values.
12. **Deletion-aware retention:** raw/user-keyed delete-policy topics ≤ 7d; compacted user-keyed topics set
   `max.compaction.lag.ms` so tombstones actually purge data. See architecture §9.

## Stack
Java 21, Spring Boot 3.x (Spring MVC + virtual threads, no WebFlux), Gradle Kotlin DSL multi-module,
Kafka (KRaft) + Confluent Schema Registry + Avro, Kafka Streams (EOS v2), Redis 7 (Lettuce),
Qdrant (gRPC client), PostgreSQL 16 (catalog metadata only, no pgvector), Resilience4j,
Micrometer + Prometheus, OpenTelemetry tracing, Spring Boot structured JSON logging.
Python 3.12 + uv for `ml/` (offline training and eval, from Phase 2). Local runtime: docker-compose.
Prod target: Kubernetes on AWS (Phase 3).

## Module layout
- `libs/event-schema` holds the Avro `.avsc` files and generated classes (`com.recsys.events.v1`).
- `libs/common` holds IDs (UUIDv7), clock, config records, observability helpers and the Redis CAS script.
- `libs/feature-store` holds the Redis key schema plus typed readers and writers shared by the stream processor and the API.
- `libs/vector-index` holds the `VectorIndex` interface, the Qdrant implementation and an in-memory test fixture.
- `libs/web-support` holds `AdmissionFilter` (load shedding), `AuthFilter` (API key / JWT), `Principals` (authorization helpers) and error mapping (Spring auto-configuration).
- `libs/openai-client` holds the embeddings, structured-output chat and Batch API clients, plus the `EmbeddingClient` interface, the OpenAI implementation (retries, breaker, rate limiting, cost metrics) and `MockEmbeddingClient`.
- `services/ingestion-api` takes `POST /v1/events`, validates the JSON, converts it to Avro and produces it.
- `services/catalog-service` takes catalog upserts, writes them to Postgres and produces `catalog.items.v1`.
- `services/stream-processor` runs the Kafka Streams topology plus the feature-writer (Redis CAS sink).
- `services/embedding-worker` consumes `catalog.items.v1`, calls OpenAI (or the mock), and writes to Qdrant and the current embeddings topic. It also runs the backfill / re-embed job (profile `backfill`).
- `services/enrichment-worker` runs LLM metadata enrichment (writing through the catalog-service PATCH endpoint) and cached explanations (published to `features.item.v1`).
- `services/recommendation-api` serves `GET /v1/recommendations` through candidate generation, ranking, re-ranking and fallback.
- `tools/event-simulator` generates synthetic catalogs and user behaviour for all four domains, and runs the freshness checks.
- `deploy/k8s` holds Kustomize base/components/overlays **generated** by `deploy/k8s/generate.py` (edit the generator, then `uv run deploy/k8s/generate.py`).
- `tools/cost/cost_model.py` is the cost model behind `docs/cost-report.md`. `config/grafana/generate_dashboards.py` generates the dashboards.
- `ml/` (Python 3.12, uv, runs in Docker) handles export, dataset, LightGBM training, gating, the registry (`ml/models`), the A/B report and the embedding-dims benchmark.

## Commands
```bash
./gradlew build                 # compile + unit tests + spotless check
./gradlew test                  # unit tests (*Test)
./gradlew integrationTest       # Testcontainers tests (*IT); needs Docker
./gradlew spotlessApply         # format
./gradlew :services:recommendation-api:bootRun
docker compose up -d            # Kafka, Schema Registry, Redis, Qdrant, Postgres, Prometheus, all services
docker compose --profile sim run --rm simulator all        # load the catalog + simulate listeners
docker compose --profile sim run --rm simulator freshness  # end-to-end freshness check (<5 s)
docker compose --profile ml run --rm ml recsys-export --out data   # then recsys-train / recsys-ab-report / recsys-promote
docker build -t recsys/ml:local ml && docker run --rm recsys/ml:local pytest -q  # ML tests (LightGBM needs libgomp → Docker)
uv run deploy/k8s/generate.py && kubectl kustomize deploy/k8s/overlays/prod-aws   # regenerate + render manifests
docker run --rm -v "$PWD/config/prometheus:/p" --entrypoint promtool prom/prometheus:v3.6.0 test rules /p/alerts_test.yml
```
- Every alert in `config/prometheus/alerts.yml` needs a `runbook_url` section in `docs/runbooks.md` and a
  `promtool` test. After editing alerts or dashboards, regenerate `deploy/k8s` (it embeds both).
- JVMs run with `MALLOC_ARENA_MAX=2` and a container memory limit (untracked native RSS caused OOM kills).
  K8s: memory limit = request, no CPU limits.
- The Gradle daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`). google-java-format breaks on newer JDKs.
- This checkout lives in an iCloud-synced folder. `~/.gradle/gradle.properties` sets
  `recsys.buildDirName=build.nosync`, so build output goes to `build.nosync/`. Delete any `* 2.java`
  conflict copies if they appear.
- Confluent `-ccs` Kafka versions are mapped to Boot's Kafka version in `recsys.java-conventions`.

## Coding standards
- Use records for DTOs and config (`@ConfigurationProperties` records), sealed interfaces for closed hierarchies,
  and constructor injection. No Lombok.
- Extension points are interfaces: `CandidateGenerator`, `Ranker`, `ReRanker`, `EmbeddingClient`, `ChatClient`.
  Per-domain behaviour is configuration (signal weights, re-rank rules, experiments), not branches.
  New strategies are new implementations, never `if` branches inside existing ones.
- Package by feature (`candidates`, `ranking`, `rerank`), not by layer.
- Formatting uses google-java-format via Spotless. Use `var` only when the type is obvious.
- Tests use JUnit 5 + AssertJ. Unit tests are `*Test` and run without Docker. Integration tests are `*IT` and use Testcontainers
  (Kafka, Redis, Qdrant, Postgres). Kafka Streams logic is tested with `TopologyTestDriver`.
- Metrics are named `recs_<component>_<thing>_<unit>`. Every log line carries `trace_id`, and none contains free text or PII.
- Public endpoints authorize with `Principals.requireUser` / `requireScope`. Never trust a `userId` parameter alone.
- Keep it simple: no new infrastructure or framework without a written trade-off in `docs/architecture.md`.
