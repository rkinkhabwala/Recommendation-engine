# Runbooks

Each alert in `config/prometheus/alerts.yml` links here. Dashboards: Grafana → folder **Recsys**.
First step for every alert: open **Recsys / SLOs** and check whether users are affected (latency,
availability, degraded share). The serving path degrades rather than fails, so "degraded but up"
is the common case.

## RecsAvailabilityBurnFast
**Meaning:** > 1.44% of `/v1/recommendations` requests return 5xx (14.4× the 99.9% SLO error rate)
for both the last hour and the last 5 minutes. The API is designed to degrade instead of erroring,
so 5xx usually means the process itself is unhealthy.
1. Check `TargetDown` and pod restarts (`kubectl -n recsys get pods`, OOMKilled?).
2. **Recsys / Serving internals** → GC pauses and heap. Memory pressure has caused multi-second
   stalls before (see architecture R17).
3. Roll back the last deploy if it correlates: `kubectl -n recsys rollout undo deploy/recommendation-api`.

## ServingP99Above100ms
**Meaning:** tail latency is over budget.
1. **Serving internals** → which stage is slow (`user_features`, `candidates`, `hydration`, `rescore`).
2. Candidates slow → Qdrant (CPU, re-indexing after a bulk enrichment or backfill: R19) or Redis latency.
   Check breaker states and generator timeouts.
3. Rescore slow → large candidate pools. Lower `recs.api.candidates.ann` temporarily.
4. Scale out: HPA should already be acting. Check `kubectl -n recsys get hpa`.

## ApiLoadShedding
**Meaning:** an API's `AdmissionFilter` is returning 503 + `Retry-After` because pods are at
`recs.admission.max-in-flight`. This is deliberate overload protection: admitted requests stay inside
the SLO instead of every request slowing down until pods are OOM-killed (seen in the capacity ramp,
docs/load-test-results.md).
1. `kubectl -n recsys get hpa`: is the HPA at `maxReplicas`? Raise it (and check node capacity /
   cluster-autoscaler events). If it is still scaling, shedding should stop within ~1–2 minutes.
2. Not at max but shedding: a dependency got slower, so each request holds its slot longer (Little's
   law). Check **Serving internals** stage latencies and `ServingCircuitOpen`.
3. Traffic spike from one client? Check request rate by API key / gateway route; rate-limit at the
   gateway.
4. Do not simply raise `RECS_MAX_IN_FLIGHT`: without CPU headroom it only trades 503s for timeouts.

## FeatureFreshnessP95Above5s
**Meaning:** user actions take more than 5 s to reach Redis.
1. **Pipeline** → consumer lag. Lag on `events.raw.v1` / repartition topics means the stream
   processor is behind. Lag on `features.*` means the feature-writer is behind (Redis slow or down).
2. Stream processor rebalancing or restoring state? Check logs for `State transition` and restore
   progress. Standby replicas shorten restores.
3. Scale the stream processor (≤ partition count) if CPU-bound.

## FallbackRateAbove2Percent
**Meaning:** more than 2% of responses are PARTIAL, ANONYMOUS, CACHED_POPULAR or STATIC.
1. **SLOs** → which level. CACHED_POPULAR/STATIC = Redis unreachable. PARTIAL = a generator is
   timing out (usually Qdrant). ANONYMOUS = user-feature reads failing or timing out.
2. Check breaker states (**Serving internals**) and the dependency's own health.

## ServingCircuitOpen
**Meaning:** the API has stopped calling Redis or Qdrant, because more than 50% of calls failed.
1. Check the dependency (ElastiCache / Qdrant pods). The breaker half-opens every 5 s and closes
   by itself on recovery.
2. While it is open, users get popular lists (Redis) or CF/trending only (Qdrant). There is no
   outage, but recommendations are worse.

## StreamConsumerLagGrowing
1. Which consumer: the stream processor, feature-writer, embedding-worker or enrichment-worker
   (`client_id`)?
2. Workers: KEDA scales them on lag. Check the ScaledObject status. For the embedding or
   enrichment worker, check OpenAI (breaker, 429s, budget).
3. Stream processor: see the freshness runbook.

## FeatureWriterStalled
**Meaning:** events are accepted but no feature writes happen.
1. feature-writer consumer (`features.*`) lag and logs. Redis reachable?
2. Stream processor running? Its health endpoint reports the Kafka Streams state; ERROR or
   PENDING_SHUTDOWN means restart it.
3. Redis lost its data? The writer replays automatically when the sentinel key disappears
   (`recs:feature-writer:sentinel`).

## CatalogOutboxBacklog
**Meaning:** committed catalog changes are not reaching Kafka.
1. catalog-service logs: `Outbox relay failed` means Kafka or Schema Registry is unreachable. Rows
   stay queued (no data loss), so fix Kafka and the relay drains the backlog automatically.
2. `recs_catalog_outbox_pending` growing while healthy means the relay is too slow. Scale
   catalog-service replicas (relays coordinate via `SKIP LOCKED`).

## EmbeddingDeadLetters
**Meaning:** items were rejected as bad input (400 / too long), not because of an outage.
1. Inspect `embedding.dlq.v1` (itemId + reason). Fix the content in the catalog, then re-upsert
   the item.

## TargetDown
1. `kubectl -n recsys describe pod …`: crash loop, OOMKilled, image pull or readiness failure.
2. Compose: `docker compose ps` and logs.

## RankerModelMissing
**Meaning:** an A/B variant configured with `lightgbm` serves the heuristic. Either no model was
promoted, or the model was refused.
1. Check `recs_api_model_info` (**Experiments & models**). Check the `candidate` and `current`
   pointers in the registry (`ml/models/ranker/<domain>/`).
2. Expected right after creating a variant. Otherwise run `recsys-train` and check whether the gate
   refused the model.

## RankerModelLoadFailed
**Meaning:** a promoted model was refused (feature-list mismatch or corrupt file). The previous
model, or the heuristic, keeps serving.
1. API logs: `Refusing ranker model`. Feature mismatch means `FeatureExtractor.FEATURES` and
   `ml/recsys_ml/__init__.py` diverged. Retrain.
2. Roll back the pointer: `recsys-promote --domain <d> --version <previous>`.

## OpenAiBudget
**Meaning:** spend crossed 50% or 80% of the monthly budget (shared ledger across replicas).
1. **OpenAI cost** dashboard → which job. Enrichment and backfills are the usual drivers.
2. At 100%, non-essential jobs (enrichment, explanations, backfills) pause by themselves.
   New-item embedding continues.
3. Raise the budget deliberately (`RECS_OPENAI_MONTHLY_BUDGET_USD`) or throttle
   (`RECS_ENRICHMENT_MAX_PER_SECOND`).

## OpenAiCircuitOpen
**Meaning:** OpenAI calls keep failing (outage, 429s, auth). **Serving is unaffected.** New items
are retrievable through CF and popularity until they are embedded.
1. 401/403 is CONFIG: check the API key secret. 429 means lower `requests-per-minute`. 5xx is an
   upstream incident; wait it out.
2. Consumers are paused, not dead-lettering. The backlog drains on recovery.

## Procedure: Qdrant data lost or corrupted
Serving keeps working (ANN and fresh-item sources fail fast, breaker opens, other candidate sources
and the popular fallback fill the list; `FallbackRateAbove2Percent` fires), but quality drops.
1. Preferred: restore the latest Qdrant snapshot (prod: per-collection snapshots to S3) and recreate
   the `items_current` alias.
2. Otherwise rebuild: the embedding-worker recreates the collection and alias at startup; reset its
   consumer group to re-read the compacted catalog and re-embed everything:
   `kubectl -n recsys scale deploy/embedding-worker --replicas=0`, then
   `kafka-consumer-groups --bootstrap-server $KAFKA --group catalog-embedder --reset-offsets --to-earliest --topic catalog.items.v1 --execute`,
   then scale back up. Content hashes live in Qdrant, so every item is re-embedded: ~5M items ×
   ~60 tokens ≈ $6 on the sync API (budget guard applies) and roughly an hour at typical rate limits.
   Watch `recs_embedding_items_total` and the collection's `points_count`.
3. `TODO(phase-4)`: re-index straight from the compacted `catalog.embeddings.*` topic (no OpenAI
   calls) for faster, OpenAI-independent recovery.
