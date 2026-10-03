# Load test results (Phase 3)

Everything below ran on one laptop: a single-node **kind** cluster (Kubernetes, `deploy/k8s/overlays/local`)
inside Docker Desktop with 10 vCPUs and 7.75 GiB, with Kafka, Redis, Qdrant, Postgres, all six services
**and the load generator** on the same node. Absolute numbers are therefore pessimistic, and the
higher steps partly measure contention. What carries over to production: **CPU per request** (the
process's own CPU time), the **failure modes** and their fixes, and **rollout behaviour**. Phase 2 compose
numbers are repeated at the end for comparison.

Tools: k6 (`load-tests/recommendations.js`, run in-cluster as a pod against the Service), the event
simulator (`tools/event-simulator`), Prometheus metrics scraped from the pods.

## 1. End to end on Kubernetes (kind)

| Check | Result |
|---|---|
| `kubectl kustomize` + `kubeconform -strict` (K8s 1.33 + CRD schemas), both overlays | 85/85 resources valid |
| Pod Security `restricted` | all six service pods admitted; only local infra and the hostPath models volume need the relaxed local label |
| Catalog load (90K items) via catalog-service → **outbox** → Kafka → embedding worker → Qdrant | 90,000 points; outbox drained to 0 pending |
| Event simulation, 6,000 users, 400 events/s for 3 min | 71,911 events, 0 rejected, 8,672 recommendation calls, 0 errors |
| Freshness (5 trials, jazz) | personalized after median **0.61 s**, max 2.13 s (target < 5 s): PASS |
| Steady 200 req/s, mixed domains, 1 API pod | p50 **2.5 ms**, p99 **68 ms**, 0 errors |

## 2. Per-pod capacity ramp (recommendation-api)

One pod (1 vCPU request, 1 GiB limit, no CPU limit), HPA pinned to 1 replica, constant-rate steps of
60 s, mixed domains (song-heavy), users with history plus cold-start users.

**First ramp: no load shedding, 768 MiB limit.** The pod handled 300 req/s (p99 58 ms) and then
**collapsed at 600 req/s**: 16% errors, p99 2.07 s, and an OOM kill. With virtual threads Tomcat no
longer limits concurrency, so excess requests queued without bound, each holding memory, until the
container was killed. That led to the `AdmissionFilter` (503 + `Retry-After` past
`recs.admission.max-in-flight`, 64 here) and the 1 GiB local limit that compose already used.

**Second ramp: with shedding.**

| Offered req/s | Achieved | p50 | p95 | p99 | Shed (503) | CPU per request | Restarts |
|---|---|---|---|---|---|---|---|
| 200 | 200 | 2.8 ms | 29 ms | 93 ms | 0 | 6.8 ms (cold JIT) | 0 |
| 400 | 400 | 3.9 ms | 51 ms | 105 ms | 0.02% | 3.9 ms | 0 |
| 600 | 597 | 8.3 ms | 75 ms | 133 ms | 0.08% | 3.2 ms | 0 |
| 800 | 799 | 7.8 ms | 64 ms | 81 ms | 0.06% | 2.6 ms | 0 |
| 1,000 | 974 | 17 ms | 90 ms | 172 ms | 0.8% | 2.4 ms | 0 |
| 1,300 | n/a | | | | 10% | 2.2 ms | 0 |

- **Capacity: ~2.6 ms of CPU per request once warm, so ~380 req/s per vCPU at 100%.** With the HPA
  targeting 60% CPU that is ~230 req/s per 1-vCPU pod. The cost model uses these numbers. The pod
  reached 800 req/s here only by borrowing idle cores (no CPU limit).
- Overload now degrades by shedding instead of crashing: **0 restarts at every step**. At 1,300 req/s
  every failure was a deliberate 503 (7,808 of 7,808).
- The 1,300 step's latency figures are **not valid**: the node itself was saturated (k6 included),
  achieved throughput collapsed and one request took minutes. It is only listed for the shedding
  behaviour.

## 3. Autoscaling

With metrics-server installed, a ramp to 700 req/s scaled the API **1 → 3 replicas within 15 s**
(fast scale-up policy), with all 3 ready after ~165 s (JVM start, then readiness). On the laptop
this then exhausted the node: local requests are deliberately below limits so the stack fits, and the
scheduler overcommitted memory. That OOM-killed an API pod and Jaeger, and the API server timed out. In
prod, memory request = limit prevents this. The local overlay now caps the HPA at 2 and gives Jaeger
512 MiB with 1% trace sampling.

## 4. Memory: untracked native RSS

After that, both new API pods were OOM-killed at **150 req/s**, below 1 GiB of heap use. The JVM
accounted for ~230 MB (heap ~110 MB committed, metaspace/code ~120 MB) while RSS was ~490 MB idle. The
missing ~260 MB was glibc malloc arenas. glibc keeps up to 8 × CPUs arenas, and with no CPU limit the
JVM, netty, gRPC and RocksDB see all node cores.

- Fix: `MALLOC_ARENA_MAX=2` for every JVM (K8s manifests and compose). Afterwards RSS stayed flat at
  ~870 MiB under a 600 req/s overload with **0 restarts**.
- Also tried: `-XX:ActiveProcessorCount=2` for all JVMs. It **hurt the API**: it also cut G1's parallel GC
  threads, young pauses averaged ~32 ms (target 20 ms), and slow in-flight requests promoted garbage
  into the old generation, which made it worse still. It is kept for the workers and stream processor
  (2 × their CPU request) and removed for the recommendation API.

## 5. Re-index storm (risk R19) reproduced

Reloading the catalog after a local Postgres reset made the enrichment worker re-enrich all 90K items
(~54 items/s), and every enrichment triggers a re-embed and a Qdrant upsert. Qdrant stayed `yellow`
(optimizing), the node ran at ~7.7 cores, and **a baseline 150 req/s saw 16–30% errors with multi-second
latency**. Pausing the enricher (`kubectl scale deploy/enrichment-worker --replicas=0`) turned Qdrant
`green` within ~25 s. This confirms R19's mitigation: throttle enrichment rollouts
(`RECS_ENRICHMENT_MAX_PER_SECOND`), use the backfill path for large ones, and give Qdrant dedicated nodes
(prod-aws `qdrant-values.yaml`: own node group, `max_optimization_threads: 2`).

## 6. Zero-downtime rollout

`kubectl rollout restart deploy/recommendation-api` (2 pods, `maxUnavailable: 0`, preStop 10 s,
graceful shutdown 20 s) under a constant 100 req/s for 4 minutes:

| Requests | Failed | p50 | p95 | p99 | Rollout duration |
|---|---|---|---|---|---|
| 24,001 | **0** | 4.6 ms | 61 ms | 181 ms | 36 s |

No connection errors, and no request lost while pods drained. The p99 comes from new pods taking a
full traffic share with a cold JIT. Mitigation in prod: ALB `slow_start.duration_seconds=60` on the
target group (in the prod-aws ingress). `TODO(phase-4)`: an in-process warm-up before readiness.

An earlier attempt at 150 req/s failed badly (35–40% errors), but that ran while the R19 storm and the
malloc OOMs (§4, §5) were still active. It is a capacity result, not a rollout result.

## 7. Caveat: late-session degradation

After about two hours of repeated overload tests on the same node, a warm 300 req/s run across two pods
still passed (0 errors) but with p99 340 ms. The `rank` stage, which is pure CPU, took 24 ms instead of
~1.2 ms. Redis was healthy (70 MB, no evictions) and Qdrant green. The likely cause is host CPU
contention or throttling on the laptop. It was not pursued further. **Re-run the ramp on real nodes
(prod-like node group, load generator off-cluster) before relying on absolute latency numbers.**

## 8. Phase 2 reference (docker compose, same laptop)

| Test | Result |
|---|---|
| k6, mixed domains | p50 2.4 ms, p99 9.6 ms (client) |
| Under concurrent 500 events/s ingest | server p99 33 ms |
| Freshness | p99 0.72 s; cross-domain relevance < 1.3 s |

## How to reproduce

```bash
kind create cluster --name recsys --config deploy/k8s/overlays/local/kind-config.yaml
docker compose build && kind load docker-image --name recsys recsys/{recommendation-api,ingestion-api,catalog-service,stream-processor,embedding-worker,enrichment-worker,event-simulator}:local
kubectl apply -k deploy/k8s/overlays/local
# metrics-server for the HPA (kind needs --kubelet-insecure-tls)
kubectl create configmap k6-scripts -n recsys --from-file=load-tests/
# then run the simulator / k6 as pods, see README "Kubernetes (kind)"
```
