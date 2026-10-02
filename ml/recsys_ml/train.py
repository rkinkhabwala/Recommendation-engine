"""Train, evaluate and (if it beats the current baseline) promote a LightGBM LambdaRank ranker.

Two-stage gating (logged clicks are position-biased toward the policy that produced them):

1. Candidate gate (offline, position-free): among items the user engaged with, does the model
   separate good outcomes (completed / liked / saved) from poor ones (early skip, click-only)
   better than the logged heuristic score? Both scores ignore position, so the comparison is fair.
   Passing models become `candidate` and are served to A/B treatment variants
   (`ranker: lightgbm:candidate`).
2. Promotion to `current`: automatically if IPS-weighted NDCG@5 on the later time slice also beats
   the logged order (rarely possible for single-click surfaces, where one positive per list makes
   IPS cancel out), otherwise after an A/B win via `recsys-promote`.
"""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime

import lightgbm as lgb
import numpy as np
import pandas as pd

from recsys_ml import FEATURES, MODEL_FEATURES, dataset, metrics, registry

PARAMS = {
    "objective": "lambdarank",
    "n_estimators": 200,
    "learning_rate": 0.05,
    "num_leaves": 31,
    "min_child_samples": 20,
    "subsample": 0.8,
    "subsample_freq": 1,
    "colsample_bytree": 0.9,
    "random_state": 7,
    "verbose": -1,
}


def features_frame(df: pd.DataFrame, position: bool = True) -> pd.DataFrame:
    x = df[[f"f_{f}" for f in FEATURES]].copy()
    x.columns = FEATURES
    # Position-as-feature absorbs position bias in logged data; it is fixed to 0 at inference.
    x["position"] = df["position"].astype(float) if position else 0.0
    return x.astype(float)


def group_sizes(df: pd.DataFrame) -> list[int]:
    return df.groupby("recommendation_id", sort=False).size().tolist()


def train_model(train: pd.DataFrame, valid: pd.DataFrame, params: dict | None = None) -> lgb.LGBMRanker:
    model = lgb.LGBMRanker(**(params or PARAMS))
    model.fit(
        features_frame(train),
        train["label"],
        group=group_sizes(train),
        eval_set=[(features_frame(valid), valid["label"])],
        eval_group=[group_sizes(valid)],
        eval_at=[5, 10],
    )
    return model


def offline_report(model: lgb.LGBMRanker, valid: pd.DataFrame, train: pd.DataFrame, catalog: pd.DataFrame) -> dict:
    v = valid.copy()
    v["model_score"] = model.predict(features_frame(v, position=False))
    prop = metrics.position_propensities(train)
    model_m = metrics.evaluate(v, "model_score", k=5, propensity=prop)
    baseline_m = metrics.evaluate(v, "position", k=5, ascending=True, propensity=prop)
    creator_of = dict(zip(catalog["item_id"], catalog["creator_id"])) if not catalog.empty else {}
    popularity = train.groupby("item_id")["label"].count().to_dict()
    catalog_size = int(catalog["item_id"].nunique()) if not catalog.empty else int(v["item_id"].nunique())
    for name, col, asc, m in (("model", "model_score", False, model_m), ("baseline", "position", True, baseline_m)):
        lists = metrics.top_k_lists(v, col, 3, ascending=asc)
        m["coverage@3"] = metrics.coverage(lists, catalog_size)
        m["diversity@3"] = metrics.intra_list_diversity(lists, creator_of)
        m["novelty@3"] = metrics.novelty(lists, popularity)
    # Post-engagement quality: position-free comparison of model vs logged heuristic score.
    engaged = v[v["label"] >= 1]
    target = (engaged["label"] >= 2).astype(int)
    model_m["quality_auc"] = metrics.auc(engaged["model_score"], target)
    baseline_m["quality_auc"] = metrics.auc(engaged["score"], target)
    model_m["engaged_rows"] = baseline_m["engaged_rows"] = int(len(engaged))
    return {"model": model_m, "baseline": baseline_m, "position_propensity": prop}


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--domain", required=True)
    ap.add_argument("--data", default="data")
    ap.add_argument("--models", default="models")
    ap.add_argument("--min-groups", type=int, default=200)
    ap.add_argument("--min-gain", type=float, default=0.0, help="required IPS-NDCG@5 gain over baseline")
    args = ap.parse_args(argv)

    served = dataset.load(args.data, "served")
    attributed = dataset.load(args.data, "attributed")
    catalog = dataset.load(args.data, "catalog")
    df = dataset.build(served, attributed, args.domain)
    if df.empty or df["recommendation_id"].nunique() < args.min_groups:
        raise SystemExit(f"not enough training groups for {args.domain}: {0 if df.empty else df['recommendation_id'].nunique()}")
    train, valid = dataset.time_split(df)
    model = train_model(train, valid)
    report = offline_report(model, valid, train, catalog)
    gain = report["model"]["ips_ndcg@5"] - report["baseline"]["ips_ndcg@5"]
    quality_gain = report["model"]["quality_auc"] - report["baseline"]["quality_auc"]
    enough = report["model"]["groups"] >= min(args.min_groups, 50)
    candidate = enough and report["model"]["engaged_rows"] >= 100 and quality_gain >= 0
    promoted = enough and gain >= args.min_gain

    version = "v" + datetime.now(UTC).strftime("%Y%m%dT%H%M%S")
    model_json = model.booster_.dump_model()
    assert model_json["feature_names"] == MODEL_FEATURES, model_json["feature_names"]
    metadata = {
        "version": version,
        "domain": args.domain,
        "features": MODEL_FEATURES,
        "objective": "lambdarank",
        "params": PARAMS,
        "trained_at": datetime.now(UTC).isoformat(),
        "data": {
            "rows": len(df),
            "groups": int(df["recommendation_id"].nunique()),
            "train_groups": int(train["recommendation_id"].nunique()),
            "valid_groups": int(valid["recommendation_id"].nunique()),
            "served_ts_min": int(df["served_ts"].min()),
            "served_ts_max": int(df["served_ts"].max()),
            "label_distribution": {str(k): int(v) for k, v in df["label"].value_counts().sort_index().items()},
        },
        "offline": report,
        "ips_ndcg5_gain": gain,
        "quality_auc_gain": quality_gain,
        "candidate": bool(candidate),
        "promoted": bool(promoted),
        "feature_importance": dict(zip(MODEL_FEATURES, map(int, model.booster_.feature_importance("gain")))),
    }
    path = registry.save(args.models, args.domain, version, model_json, metadata)
    if candidate or promoted:
        registry.promote(args.models, args.domain, version, "candidate")
    if promoted:
        registry.promote(args.models, args.domain, version)
    print(json.dumps({"version": version, "path": str(path), "candidate": candidate, "promoted": promoted, "ips_ndcg5_gain": round(gain, 4), "quality_auc_gain": round(quality_gain, 4), **report}, indent=2, default=float))


if __name__ == "__main__":
    np.set_printoptions(precision=4)
    main()
