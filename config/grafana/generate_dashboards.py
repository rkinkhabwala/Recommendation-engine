"""Generates the provisioned Grafana dashboards (config/grafana/dashboards/*.json).

Run: python3 config/grafana/generate_dashboards.py   (stdlib only; output is committed)
"""

import json
from pathlib import Path

DS = {"type": "prometheus", "uid": "prometheus"}
OUT = Path(__file__).parent / "dashboards"


def target(expr, legend="", ref="A"):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref}


def panel(title, exprs, kind="timeseries", unit="short", w=12, h=8, thresholds=None, desc="", stack=False, decimals=None):
    targets = [target(e, l, chr(65 + i)) for i, (e, l) in enumerate(exprs)]
    p = {
        "type": kind,
        "title": title,
        "description": desc,
        "datasource": DS,
        "targets": targets,
        "gridPos": {"w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {}}, "overrides": []},
        "options": {},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    if thresholds:
        p["fieldConfig"]["defaults"]["thresholds"] = {
            "mode": "absolute",
            "steps": [{"color": "green", "value": None}] + [{"color": c, "value": v} for v, c in thresholds],
        }
        if kind == "timeseries":
            p["fieldConfig"]["defaults"]["custom"]["thresholdsStyle"] = {"mode": "line"}
    if stack and kind == "timeseries":
        p["fieldConfig"]["defaults"]["custom"]["stacking"] = {"mode": "normal"}
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background"}
    return p


def layout(panels):
    x = y = row_h = 0
    for i, p in enumerate(panels):
        w, h = p["gridPos"]["w"], p["gridPos"]["h"]
        if x + w > 24:
            x, y = 0, y + row_h
            row_h = 0
        p["gridPos"].update({"x": x, "y": y})
        p["id"] = i + 1
        x += w
        row_h = max(row_h, h)
    return panels


def dashboard(uid, title, panels, variables=None, desc=""):
    return {
        "uid": uid,
        "title": title,
        "description": desc,
        "tags": ["recsys"],
        "timezone": "utc",
        "schemaVersion": 39,
        "refresh": "30s",
        "time": {"from": "now-1h", "to": "now"},
        "templating": {"list": variables or []},
        "panels": layout(panels),
    }


def var(name, query, label=None):
    return {
        "name": name,
        "label": label or name,
        "type": "query",
        "datasource": DS,
        "query": {"query": query, "refId": "v"},
        "definition": query,
        "includeAll": True,
        "multi": True,
        "current": {"text": "All", "value": "$__all"},
        "refresh": 2,
    }


def q(metric_quantile, metric, by="", window="5m", where=""):
    grp = f"le{',' + by if by else ''}"
    return f"histogram_quantile({metric_quantile}, sum by ({grp}) (rate({metric}_bucket{{{where}}}[{window}])))"


domain_var = var("domain", 'label_values(recs_api_request_seconds_count, domain)')
api_where = 'domain=~"$domain"'

slo = dashboard(
    "recsys-slo",
    "Recsys / SLOs",
    [
        panel("Serving p50 (target < 30 ms)", [(q(0.5, "recs_api_request_seconds", where=api_where), "p50")], "stat", "s", 6, 4, [(0.03, "red")], decimals=3),
        panel("Serving p99 (target < 100 ms)", [(q(0.99, "recs_api_request_seconds", where=api_where), "p99")], "stat", "s", 6, 4, [(0.1, "red")], decimals=3),
        panel("Freshness p95, event → Redis (target < 5 s)", [(q(0.95, "recs_feature_freshness_seconds", where='feature="user_short_term"'), "p95")], "stat", "s", 6, 4, [(5, "red")], decimals=2),
        panel("Degraded responses (target < 2%)", [(f'sum(rate(recs_api_fallback_total{{level!="NONE",{api_where}}}[5m])) / sum(rate(recs_api_fallback_total{{{api_where}}}[5m]))', "degraded")], "stat", "percentunit", 6, 4, [(0.02, "red")], decimals=2),
        panel("Serving latency by domain", [(q(0.99, "recs_api_request_seconds", "domain", where=api_where), "p99 {{domain}}"), (q(0.5, "recs_api_request_seconds", "domain", where=api_where), "p50 {{domain}}")], unit="s", w=12, thresholds=[(0.1, "red")]),
        panel("Responses by fallback level", [(f"sum by (level) (rate(recs_api_fallback_total{{{api_where}}}[5m]))", "{{level}}")], unit="reqps", w=12, stack=True),
        panel("Freshness (user short-term, cross-domain)", [(q(0.5, "recs_feature_freshness_seconds", where='feature="user_short_term"'), "st p50"), (q(0.99, "recs_feature_freshness_seconds", where='feature="user_short_term"'), "st p99"), (q(0.95, "recs_feature_freshness_seconds", where='feature="user_cross_domain"'), "cross p95")], unit="s", w=12, thresholds=[(5, "red")]),
        panel("Availability: non-5xx share of /v1/recommendations", [('sum(rate(http_server_requests_seconds_count{uri="/v1/recommendations",status!~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{uri="/v1/recommendations"}[5m]))', "availability")], unit="percentunit", w=12, thresholds=[(0.999, "green")], decimals=4),
        panel("Error budget burn rate (99.9% SLO, 1h)", [('(1 - (sum(rate(http_server_requests_seconds_count{uri="/v1/recommendations",status!~"5.."}[1h])) / sum(rate(http_server_requests_seconds_count{uri="/v1/recommendations"}[1h])))) / 0.001', "burn 1h")], unit="short", w=12, thresholds=[(1, "orange"), (14.4, "red")], desc="1 = consuming budget exactly at the SLO rate; 14.4 pages (2% of a 30-day budget in 1 h)"),
        panel("Request rate by domain", [("sum by (domain) (rate(recs_api_request_seconds_count[5m]))", "{{domain}}")], unit="reqps", w=12, stack=True),
    ],
    [domain_var],
    "Serving latency, freshness, degradation and availability against the targets in docs/architecture.md §1.",
)

pipeline = dashboard(
    "recsys-pipeline",
    "Recsys / Pipeline",
    [
        panel("Events ingested by result", [("sum by (result) (rate(recs_ingest_events_total[5m]))", "{{result}}")], unit="ops", stack=True),
        panel("Rejected events by reason", [('sum by (reason) (rate(recs_ingest_events_total{result="rejected"}[5m]))', "{{reason}}")], unit="ops"),
        panel("Consumer lag (max per client)", [("max by (client_id) (kafka_consumer_fetch_manager_records_lag_max)", "{{client_id}}")], unit="short", w=24, thresholds=[(10000, "red")]),
        panel("Feature writes by kind/result", [("sum by (kind, result) (rate(recs_feature_writes_total[5m]))", "{{kind}} {{result}}")], unit="ops", stack=True),
        panel("Stream threads: records processed", [("sum by (thread_id) (rate(kafka_stream_thread_process_total[5m]))", "{{thread_id}}")], unit="ops"),
        panel("Catalog outbox", [("recs_catalog_outbox_pending", "pending rows"), ("recs_catalog_outbox_oldest_seconds", "oldest (s)")], unit="short", thresholds=[(60, "red")]),
        panel("Embeddings by result", [("sum by (result) (rate(recs_embedding_items_total[5m]))", "{{result}}")], unit="ops", stack=True),
        panel("Enrichment by result", [("sum by (result) (rate(recs_enrichment_items_total[5m]))", "{{result}}")], unit="ops", stack=True),
        panel("Explanations generated by domain", [("sum by (domain) (rate(recs_explanations_total[5m]))", "{{domain}}")], unit="ops", stack=True),
    ],
    desc="Ingestion → Kafka Streams → feature-writer → Redis; catalog outbox; embedding and enrichment workers.",
)

serving = dashboard(
    "recsys-serving",
    "Recsys / Serving internals",
    [
        panel("Stage latency (max over 1m)", [("max by (stage) (max_over_time(recs_api_stage_seconds_max[1m]))", "{{stage}}")], unit="s"),
        panel("Stage latency (mean)", [("sum by (stage) (rate(recs_api_stage_seconds_sum[5m])) / sum by (stage) (rate(recs_api_stage_seconds_count[5m]))", "{{stage}}")], unit="s"),
        panel("Candidate generators: timeouts and errors", [('sum by (source, result) (rate(recs_api_generator_total{result!="ok"}[5m]))', "{{source}} {{result}}")], unit="ops"),
        panel("Circuit breakers (1 = state active)", [('resilience4j_circuitbreaker_state{state!="closed"}', "{{name}} {{state}}")], unit="short"),
        panel("L1 cache hit ratio", [('sum by (cache) (rate(cache_gets_total{result="hit"}[5m])) / sum by (cache) (rate(cache_gets_total[5m]))', "{{cache}}")], unit="percentunit"),
        panel("Served-log queue and drops", [("recs_api_served_log_queue", "queue"), ('sum by (result) (rate(recs_api_served_log_total[5m]))', "{{result}}")], unit="short"),
        panel("GC pause (max)", [('max by (instance, action) (jvm_gc_pause_seconds_max)', "{{instance}} {{action}}")], unit="s", thresholds=[(0.05, "orange")]),
        panel("Heap used", [('sum by (instance) (jvm_memory_used_bytes{area="heap"})', "{{instance}}")], unit="bytes"),
        panel("Admission: in-flight vs limit", [("max by (application, instance) (recs_http_in_flight)", "{{instance}}"), ("max by (application) (recs_http_max_in_flight)", "limit {{application}}")], unit="short"),
        panel("Load shed (503) rate", [("sum by (application) (rate(recs_http_admission_rejected_total[5m]))", "{{application}}")], unit="ops", thresholds=[(1, "red")]),
    ],
    desc="Per-stage latency, candidate generator health, breakers, caches and JVM.",
)

ml = dashboard(
    "recsys-ml",
    "Recsys / Experiments & models",
    [
        panel("Online CTR (played / impressed) by variant", [(f'sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="PLAYED",{api_where}}}[15m])) / sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="IMPRESSED",{api_where}}}[15m]))', "{{domain}} {{variant}}")], unit="percentunit"),
        panel("Completion rate by variant", [(f'sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="COMPLETED",{api_where}}}[15m])) / sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="PLAYED",{api_where}}}[15m]))', "{{domain}} {{variant}}")], unit="percentunit"),
        panel("Early-skip rate by variant", [(f'sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="SKIPPED_EARLY",{api_where}}}[15m])) / sum by (domain, variant) (rate(recs_online_outcomes_total{{outcome="PLAYED",{api_where}}}[15m]))', "{{domain}} {{variant}}")], unit="percentunit"),
        panel("Served items by variant (traffic split)", [(f"sum by (domain, variant) (rate(recs_online_served_items_total{{{api_where}}}[15m]))", "{{domain}} {{variant}}")], unit="ops", stack=True),
        panel("Loaded ranker models", [("recs_api_model_info", "{{domain}} {{pointer}} {{version}}")], "table", w=12),
        panel("Ranker fallbacks (lightgbm variant without a model)", [("sum by (domain, variant) (rate(recs_api_ranker_fallback_total[5m]))", "{{domain}} {{variant}}")], unit="ops"),
        panel("Exploration share of outcomes", [('sum by (domain) (rate(recs_online_outcomes_total{explore="true"}[15m])) / sum by (domain) (rate(recs_online_outcomes_total[15m]))', "{{domain}}")], unit="percentunit"),
        panel("Model load failures", [("sum by (domain) (increase(recs_api_model_load_failures_total[1h]))", "{{domain}}")], unit="short", thresholds=[(1, "red")]),
    ],
    [domain_var],
    "Online metrics per A/B variant (offline analysis: ml/recsys_ml/ab_report.py) and model registry state.",
)

openai = dashboard(
    "recsys-openai",
    "Recsys / OpenAI cost & reliability",
    [
        panel("Budget used (month, alert 50% / 80%)", [("max(recs_openai_budget_used_ratio)", "used")], "stat", "percentunit", 6, 6, [(0.5, "orange"), (0.8, "red")]),
        panel("Cost today (USD)", [("sum(increase(recs_openai_cost_usd_total[24h]))", "24h")], "stat", "currencyUSD", 6, 6, decimals=2),
        panel("Cost by job (USD/h)", [("sum by (job) (rate(recs_openai_cost_usd_total[1h])) * 3600", "{{job}}")], unit="currencyUSD", w=12, h=6, stack=True),
        panel("Tokens by model and job", [("sum by (model, job) (rate(recs_openai_tokens_total[5m]))", "{{model}} {{job}}")], unit="short"),
        panel("Requests by outcome", [("sum by (job, outcome) (rate(recs_openai_request_seconds_count[5m]))", "{{job}} {{outcome}}")], unit="reqps", stack=True),
        panel("Request latency p95", [(q(0.95, "recs_openai_request_seconds", "job"), "{{job}}")], unit="s"),
    ],
    desc="Token usage, cost per job, budget guard and API reliability. Never on the serving path.",
)

OUT.mkdir(exist_ok=True)
for d in (slo, pipeline, serving, ml, openai):
    (OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=2) + "\n")
    print("wrote", d["uid"], len(d["panels"]), "panels")
