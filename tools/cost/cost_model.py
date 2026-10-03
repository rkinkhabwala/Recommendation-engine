"""Monthly cost model for the production design (AWS us-east-1 + OpenAI).

    python3 tools/cost/cost_model.py            # markdown tables (pasted into docs/cost-report.md)
    python3 tools/cost/cost_model.py --json     # machine-readable

Standard library only. Every number that matters is in PRICES or WORKLOAD below, with its source.
Prices are list prices noted at the time of writing and change: verify them against the AWS and
OpenAI pricing pages before using this for a budget. Measured inputs (per-pod capacity, tokens per
call, explanation cache-miss rate) come from the kind/compose runs in docs/load-test-results.md.
"""

import argparse
import json
import math

HOURS = 730  # hours per month

# ------------------------------------------------------------------------------------------ prices
# USD, us-east-1, on-demand list prices. VERIFY before use.
PRICES = {
    "eks_cluster_hour": 0.10,
    "m7g.2xlarge_hour": 0.3264,  # 8 vCPU / 32 GiB, Graviton (general node group)
    "r7g.xlarge_hour": 0.2142,  # 4 vCPU / 32 GiB (Qdrant node group)
    "msk_m7g.xlarge_hour": 0.408,  # per broker
    "msk_storage_gb_month": 0.10,
    "msk_tiered_gb_month": 0.06,
    "elasticache_r7g.large_hour": 0.219,  # 13 GiB per node
    "rds_r7g.large_hour": 0.239,  # single-AZ; Multi-AZ doubles instance cost
    "rds_storage_gb_month": 0.115,
    "ebs_gp3_gb_month": 0.08,
    "s3_gb_month": 0.023,
    "alb_hour": 0.0225,
    "alb_lcu_hour": 0.008,
    "nat_gateway_hour": 0.045,
    "nat_gb": 0.045,
    "cross_az_gb": 0.02,  # $0.01 out + $0.01 in
    "cloudwatch_logs_ingest_gb": 0.50,
    "savings_plan_discount": 0.28,  # 1-yr no-upfront Compute Savings Plan (EC2 nodes only), approx.
    # OpenAI, USD per 1M tokens. Batch API is 50% off (24 h turnaround).
    "te3s_per_m": 0.02,
    "gpt5mini_in_per_m": 0.25,
    "gpt5mini_out_per_m": 2.00,
    "batch_discount": 0.5,
}

# ---------------------------------------------------------------------------------------- workload
WORKLOAD = {
    # Spec scale: 1M DAU, ~50K events/s peak, 5M items.
    "dau": 1_000_000,
    "events_peak_per_s": 50_000,
    "peak_to_avg": 2.5,  # diurnal; HPA/KEDA follow the curve
    "event_bytes": 400,
    "items": 5_000_000,
    # Serving: ~60 recommendation requests per DAU per day (next-track, feeds, home), peak 2.5x avg.
    "recs_per_dau_day": 60,
    "rec_response_kb": 6,
    # Measured: per-pod (1 vCPU) sustainable req/s inside the SLO, from the kind capacity ramp.
    "api_rps_per_pod": 380,  # ~2.6 ms CPU/request warm (800 req/s step) → 1 vCPU at 100%
    "hpa_target_util": 0.6,
    "ingest_events_per_pod_s": 4_000,  # 0.5 vCPU pod, batches of 10. ASSUMPTION: not measured in Phase 3
    # Kafka: raw events dominate; repartitions + served/attributed/features add ~3x more topic traffic.
    "kafka_traffic_multiplier": 4.0,
    "kafka_hot_retention_days": 1,  # local broker storage; the rest of the 7 d goes to tiered storage
    "kafka_retention_days": 7,
    "zstd_ratio": 2.0,
    # Offline store: Parquet in S3 (served, attributed, events), 90 d retention.
    "s3_parquet_gb_day": 250,
    "s3_retention_days": 90,
    "log_gb_day": 30,
    # OpenAI (measured tokens per call with the mock clients' counters; embedding text assumed).
    "embed_tokens_per_item": 60,  # metadata-only text (no lyrics)
    "catalog_churn_per_day": 0.02,  # share of items created/changed per day → re-embedded
    "new_users_per_day": 30_000,
    "onboarding_tokens": 40,
    "enrich_share_of_new_items": 0.4,  # items missing moods/themes/topics
    "enrich_tokens_in": 125,
    "enrich_tokens_out": 28,
    "explanation_sample": 0.20,  # recs.enrichment.explanation-sample-percent
    "explanation_top_n": 3,
    "explanation_miss_rate": 0.05,  # (domain, reason, seed, item) pairs not yet cached; 25% in the
    # 6k-user simulation, far lower at scale where popular pairs repeat. Scenarios below.
    "explanation_tokens_in": 95,
    "explanation_tokens_out": 18,
}


def nodes_for(cpu: float, mem_gib: float, node_cpu=8, node_mem=32, alloc=0.85) -> int:
    return max(math.ceil(cpu / (node_cpu * alloc)), math.ceil(mem_gib / (node_mem * alloc)))


def aws(w=WORKLOAD, p=PRICES):
    rows = []

    def add(group, item, monthly, note):
        rows.append({"group": group, "item": item, "usd": round(monthly, 0), "note": note})

    avg_rps = w["dau"] * w["recs_per_dau_day"] / 86_400
    peak_rps = avg_rps * w["peak_to_avg"]
    per_pod = w["api_rps_per_pod"]  # at the knee; the HPA keeps pods at hpa_target_util of it
    api_peak = max(6, math.ceil(peak_rps / (per_pod * w["hpa_target_util"])))
    api_avg = max(6, math.ceil(avg_rps / (per_pod * w["hpa_target_util"])))
    ev_avg = w["events_peak_per_s"] / w["peak_to_avg"]
    ingest_avg = max(3, math.ceil(ev_avg / w["ingest_events_per_pod_s"]))
    stream_avg = 8  # 6 min .. 12 max (KEDA on lag), 2 vCPU / 4 GiB
    # (cpu, mem GiB) per replica x average replicas
    pods = {
        "recommendation-api": (1.0, 1.5, api_avg),
        "ingestion-api": (0.5, 0.75, ingest_avg),
        "catalog-service": (0.5, 0.75, 2),
        "stream-processor": (2.0, 4.0, stream_avg),
        "embedding-worker": (0.5, 1.0, 2),
        "enrichment-worker": (0.5, 0.75, 1),
        "schema-registry": (0.5, 1.0, 2),
        "platform (Prometheus, Grafana, OTel collector, KEDA, ESO, ALB ctl)": (6.0, 24.0, 1),
    }
    cpu = sum(c * n for c, _, n in pods.values())
    mem = sum(m * n for _, m, n in pods.values())
    general = max(3, nodes_for(cpu, mem))  # >= 1 per AZ
    add("Compute", "EKS control plane", p["eks_cluster_hour"] * HOURS, "1 cluster")
    add("Compute", f"General nodes: {general} x m7g.2xlarge (avg)", general * p["m7g.2xlarge_hour"] * HOURS,
        f"{cpu:.0f} vCPU / {mem:.0f} GiB requested at avg load; API avg {api_avg} / peak {api_peak} pods "
        f"({avg_rps:.0f} / {peak_rps:.0f} req/s)")
    add("Compute", "Qdrant nodes: 3 x r7g.xlarge", 3 * p["r7g.xlarge_hour"] * HOURS,
        "5M x 512 int8-quantized in RAM, originals on disk; 3 replicas")
    add("Storage", "EBS gp3: Qdrant 3x100 GB, Streams state 12x50 GB, Prometheus 200 GB",
        (300 + 600 + 200) * p["ebs_gp3_gb_month"], "")

    raw_mb_s = ev_avg * w["event_bytes"] / 1e6
    daily_gb = raw_mb_s * 86_400 / 1e3 * w["kafka_traffic_multiplier"] / w["zstd_ratio"]
    hot_gb = daily_gb * w["kafka_hot_retention_days"] * 3  # RF3
    tiered_gb = daily_gb * (w["kafka_retention_days"] - w["kafka_hot_retention_days"])  # 1 copy
    add("Kafka (MSK)", "6 x kafka.m7g.xlarge brokers (2 per AZ)", 6 * p["msk_m7g.xlarge_hour"] * HOURS,
        f"peak {w['events_peak_per_s'] * w['event_bytes'] / 1e6:.0f} MB/s raw in, ~{w['kafka_traffic_multiplier']:.0f}x total topic traffic")
    add("Kafka (MSK)", f"Broker storage {hot_gb / 1e3:.1f} TB (1 d hot, RF3)", hot_gb * p["msk_storage_gb_month"], "zstd")
    add("Kafka (MSK)", f"Tiered storage {tiered_gb / 1e3:.1f} TB (days 2-7)", tiered_gb * p["msk_tiered_gb_month"], "")
    # Client <-> broker traffic crossing AZs (2/3 of it without rack-aware fetch). Producers + the
    # stream processor's consumers/repartitions; follower fetching (client.rack) removes most reads.
    # 2/3 of produce traffic crosses AZs; consumers fetch from same-AZ followers, leaving ~half.
    cross_gb = daily_gb * 30 * (2 / 3) * 0.5
    add("Network", f"Cross-AZ Kafka client traffic ~{cross_gb / 1e3:.0f} TB", cross_gb * p["cross_az_gb"],
        "with client.rack follower fetching; ~2x without")

    redis_nodes = 6  # 3 shards x (primary + replica), 39 GiB primary capacity for ~25 GB working set
    add("Redis (ElastiCache)", f"{redis_nodes} x cache.r7g.large (3 shards + replicas)",
        redis_nodes * p["elasticache_r7g.large_hour"] * HOURS, "~25 GB features (user 15 + item 7.5)")
    add("Postgres (RDS)", "db.r7g.large Multi-AZ + 100 GB gp3",
        2 * p["rds_r7g.large_hour"] * HOURS + 2 * 100 * p["rds_storage_gb_month"], "catalog metadata only")

    s3_gb = w["s3_parquet_gb_day"] * w["s3_retention_days"]
    add("Storage", f"S3 offline store ~{s3_gb / 1e3:.0f} TB (90 d) + models", s3_gb * p["s3_gb_month"], "Parquet")

    resp_gb_h = avg_rps * w["rec_response_kb"] * 3600 / 1e6
    ingest_gb_h = ev_avg / 10 * 4 * 3600 / 1e6
    lcu = max(resp_gb_h + ingest_gb_h, (avg_rps + ev_avg / 10) / 1000 * 1)  # bytes vs rule evals
    add("Network", "Internal ALB", (p["alb_hour"] + lcu * p["alb_lcu_hour"]) * HOURS, f"~{lcu:.0f} LCU avg")
    add("Network", "NAT gateways (3 AZ; OpenAI + ECR egress)", 3 * p["nat_gateway_hour"] * HOURS + 200 * p["nat_gb"], "")
    add("Observability", f"CloudWatch log ingest {w['log_gb_day']} GB/day", w["log_gb_day"] * 30 * p["cloudwatch_logs_ingest_gb"],
        "metrics/traces self-hosted (in platform pods)")
    nodes_cost = sum(r["usd"] for r in rows if r["item"].startswith(("General nodes", "Qdrant nodes")))
    return rows, nodes_cost, {"avg_rps": avg_rps, "peak_rps": peak_rps, "api_avg": api_avg, "api_peak": api_peak}


def openai(w=WORKLOAD, p=PRICES, miss_rate=None):
    miss = w["explanation_miss_rate"] if miss_rate is None else miss_rate
    rows = []

    def add(item, monthly, note):
        rows.append({"item": item, "usd": round(monthly, 2), "note": note})

    m = 1e6
    churn_items = w["items"] * w["catalog_churn_per_day"] * 30
    add("Catalog embeddings (changed/new items)", churn_items * w["embed_tokens_per_item"] / m * p["te3s_per_m"],
        f"{churn_items / 1e6:.1f}M items/mo, text-embedding-3-small 512d, sync worker")
    add("Onboarding text embeddings", w["new_users_per_day"] * 30 * w["onboarding_tokens"] / m * p["te3s_per_m"], "PII-scrubbed")
    enrich = churn_items * w["enrich_share_of_new_items"]
    enrich_cost = enrich * (w["enrich_tokens_in"] * p["gpt5mini_in_per_m"] + w["enrich_tokens_out"] * p["gpt5mini_out_per_m"]) / m
    add("Metadata enrichment (gpt-5-mini, structured outputs)", enrich_cost, f"{enrich / 1e6:.1f}M items/mo")
    lists = w["dau"] * w["recs_per_dau_day"] * 30
    gens = lists * w["explanation_sample"] * w["explanation_top_n"] * miss
    expl = gens * (w["explanation_tokens_in"] * p["gpt5mini_in_per_m"] + w["explanation_tokens_out"] * p["gpt5mini_out_per_m"]) / m
    add(f"Explanations (gpt-5-mini), miss rate {miss:.0%}", expl, f"{gens / 1e6:.0f}M generations/mo")
    one_off = w["items"] * w["embed_tokens_per_item"] / m * p["te3s_per_m"] * p["batch_discount"]
    return rows, one_off


def table(rows, cols):
    out = ["| " + " | ".join("USD" if c == "usd" else c.title() for c in cols) + " |", "|" + "---|" * len(cols)]
    for r in rows:
        out.append("| " + " | ".join(f"${r[c]:,.0f}" if c == "usd" and r[c] >= 10 else (f"${r[c]:,.2f}" if c == "usd" else str(r[c])) for c in cols) + " |")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    a_rows, nodes_cost, sizing = aws()
    o_rows, one_off = openai()
    a_total = sum(r["usd"] for r in a_rows)
    o_total = sum(r["usd"] for r in o_rows)
    committed = a_total - nodes_cost * PRICES["savings_plan_discount"]
    scen = {f"{m:.0%}": round(sum(r["usd"] for r in openai(miss_rate=m)[0]), 0) for m in (0.01, 0.05, 0.25)}
    if args.json:
        print(json.dumps({"aws": a_rows, "openai": o_rows, "aws_total": a_total, "openai_total": o_total,
                          "aws_with_savings_plan": committed, "openai_one_off_backfill": one_off,
                          "explanation_scenarios": scen, "sizing": sizing}, indent=2))
        return
    print("## AWS (monthly, on-demand)\n")
    print(table(a_rows, ["group", "item", "usd", "note"]))
    print(f"\n**AWS total: ${a_total:,.0f}/month** on-demand; ~${committed:,.0f} with a 1-yr Compute Savings Plan on nodes.\n")
    print("## OpenAI (monthly)\n")
    print(table(o_rows, ["item", "usd", "note"]))
    print(f"\n**OpenAI total: ${o_total:,.0f}/month.** One-off full-catalog (re-)embed via Batch API: ${one_off:,.2f}.\n")
    print("Explanation cache-miss scenarios (OpenAI total/month): " + ", ".join(f"{k} → ${v:,.0f}" for k, v in scen.items()))
    print(f"\nSizing: {sizing['avg_rps']:.0f} avg / {sizing['peak_rps']:.0f} peak rec req/s → API pods {sizing['api_avg']} avg / {sizing['api_peak']} peak.")
    print(f"Cost per 1k recommendation requests (AWS+OpenAI): ${(a_total + o_total) / (sizing['avg_rps'] * 86400 * 30 / 1000):.4f}")


if __name__ == "__main__":
    main()
