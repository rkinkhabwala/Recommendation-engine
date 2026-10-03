# Cost report (Phase 3)

Monthly run cost of the production design at spec scale (1M DAU, ~50K events/s peak, 5M items),
single region (AWS us-east-1), from `tools/cost/cost_model.py`. Re-run it after changing any input:

```bash
python3 tools/cost/cost_model.py          # tables below
python3 tools/cost/cost_model.py --json
```

> **Verify pricing.** All prices are on-demand list prices written into `PRICES` at the time of
> writing. AWS and OpenAI change prices; check the current pricing pages before using these numbers
> for a budget. Treat totals as ±30%.

## Summary

| | Monthly |
|---|---|
| AWS infrastructure | **~$7.5k** on-demand (~$7.0k with a 1-yr Compute Savings Plan on nodes) |
| OpenAI | **~$3.3k** at a 5% explanation cache-miss rate. Range $0.75k–$16k, see below |
| Total | **~$10.8k/month**, ≈ **$0.006 per 1,000 recommendation requests** |

Two findings matter more than the totals:

1. **Explanations are ~97% of the OpenAI bill**, and the number is the least certain one in the
   model. Embeddings are almost free: the full 5M-item catalog re-embeds for **~$3** via the Batch
   API (512-d text-embedding-3-small, metadata only), so changing models or dimensions costs almost
   nothing. Explanations are keyed by (domain, reason, seed item, item), so cost depends on how often a
   served pair has never been explained before. That was 25% in the 6k-user simulation and should be
   far lower at scale, where popular pairs repeat, but it is not measured.
2. **Kafka (MSK) is the largest AWS line (~$2.7k incl. storage)**, driven by 7-day retention of
   ~20 MB/s of raw events plus the stream processor's repartition traffic.

## Inputs that drive the numbers

| Input | Value | Source |
|---|---|---|
| Recommendation requests | 60 per DAU per day → ~690 avg / ~1,740 peak req/s | assumption |
| API CPU per request | ~2.6 ms warm → ~380 req/s per vCPU at 100%, HPA target 60% | **measured** (kind capacity ramp, docs/load-test-results.md) |
| Ingestion | 4,000 events/s per 0.5 vCPU pod | assumption, not measured in Phase 3 |
| Kafka | 400 B/event, ×4 topic traffic (repartitions, served, attributed, features), zstd 2× | assumption + architecture §4 |
| Redis working set | ~25 GB | architecture §4 capacity notes |
| Qdrant | 5M × 512 int8-quantized in RAM, 3 replicas | architecture §4 |
| Tokens per call | enrichment 125 in / 28 out; explanation 95 in / 18 out | **measured** (token counters, mock clients) |
| Embedding text | 60 tokens per item | assumption (mock token count is not representative) |
| Catalog churn | 2% of items/day re-embedded, 40% of those enriched | assumption |
| Explanations | 20% of lists sampled × top 3 × 5% miss rate | config + assumption |

## AWS (monthly, on-demand)

| Group | Item | USD | Note |
|---|---|---|---|
| Compute | EKS control plane | $73 | 1 cluster |
| Compute | General nodes: 5 x m7g.2xlarge (avg) | $1,191 | 34 vCPU / 75 GiB requested at avg load; API avg 6 / peak 8 pods (694 / 1736 req/s) |
| Compute | Qdrant nodes: 3 x r7g.xlarge | $469 | 5M x 512 int8-quantized in RAM, originals on disk; 3 replicas |
| Storage | EBS gp3: Qdrant 3x100 GB, Streams state 12x50 GB, Prometheus 200 GB | $88 |  |
| Kafka (MSK) | 6 x kafka.m7g.xlarge brokers (2 per AZ) | $1,787 | peak 20 MB/s raw in, ~4x total topic traffic |
| Kafka (MSK) | Broker storage 4.1 TB (1 d hot, RF3) | $415 | zstd |
| Kafka (MSK) | Tiered storage 8.3 TB (days 2-7) | $498 |  |
| Network | Cross-AZ Kafka client traffic ~14 TB | $276 | with client.rack follower fetching; ~2x without |
| Redis (ElastiCache) | 6 x cache.r7g.large (3 shards + replicas) | $959 | ~25 GB features (user 15 + item 7.5) |
| Postgres (RDS) | db.r7g.large Multi-AZ + 100 GB gp3 | $372 | catalog metadata only |
| Storage | S3 offline store ~22 TB (90 d) + models | $518 | Parquet |
| Network | Internal ALB | $272 | ~44 LCU avg |
| Network | NAT gateways (3 AZ; OpenAI + ECR egress) | $108 |  |
| Observability | CloudWatch log ingest 30 GB/day | $450 | metrics/traces self-hosted (in platform pods) |

**AWS total: $7,476/month** on-demand; ~$7,011 with a 1-yr Compute Savings Plan on nodes.

## OpenAI (monthly)

| Item | USD | Note |
|---|---|---|
| Catalog embeddings (changed/new items) | $3.60 | 3.0M items/mo, text-embedding-3-small 512d, sync worker |
| Onboarding text embeddings | $0.72 | PII-scrubbed |
| Metadata enrichment (gpt-5-mini, structured outputs) | $105 | 1.2M items/mo |
| Explanations (gpt-5-mini), miss rate 5% | $3,226 | 54M generations/mo |

**OpenAI total: $3,336/month.** One-off full-catalog (re-)embed via Batch API: $3.00.

Explanation cache-miss scenarios (OpenAI total/month): 1% → $754, 5% → $3,336, 25% → $16,242

Sizing: 694 avg / 1736 peak rec req/s → API pods 6 avg / 8 peak.
Cost per 1k recommendation requests (AWS+OpenAI): $0.0060

## Recommendations

1. **Cap explanation spend before launch.** Set a production `RECS_OPENAI_MONTHLY_BUDGET_USD`
   (e.g. $1,500). The shared Redis ledger enforces it across all worker replicas. Past it, the API
   keeps serving template explanations, so users see no failure. Also start with
   `RECS_EXPLANATION_SAMPLE_PERCENT=5` and measure the real miss rate from
   `recs_explanations_total{result="generated"}` against lookups before raising it. At 1% miss rate
   OpenAI drops to ~$750/month.
2. **Explanations through the Batch API** (50% off, 24 h turnaround) suit the long tail, since the
   template fallback already covers the first view. `TODO(phase-4)`.
3. **Kafka:** keep 1 day hot with tiered storage for days 2–7 (already modelled). Use rack-aware
   consumers (`client.rack`) so stream-processor reads stay in-AZ (cross-AZ traffic is modelled at half
   of produce traffic with follower fetching, about 2× that without). Revisit retention of
   `recs.served.v1`, which only needs to outlive the attribution window plus the S3 export.
4. **Compute:** nodes are ~25% of AWS cost. Use a Savings Plan for the stable floor (min replicas,
   Qdrant), and let the HPA/KEDA-scaled remainder ride on-demand, or Spot for the workers, which are
   idempotent and lag-scaled.
5. **Redis:** the user working set has a 30-day idle TTL. If DAU/MAU is low, shortening it to 14 days
   roughly halves the user share of memory.

## Not included

Data transfer to end users (behind the client's own gateway/CDN), the IdP, CI/CD, staging and dev
environments (dev OpenAI is capped at $50/month by the budget guard), support plans and people.
