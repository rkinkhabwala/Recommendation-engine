"""End-to-end on synthetic logs: dataset -> train -> registry -> A/B report."""

import json

import numpy as np
import pandas as pd

from recsys_ml import FEATURES, MODEL_FEATURES, ab_report, dataset, registry, train


def synthetic_logs(n_lists=400, size=8, seed=1):
    rng = np.random.default_rng(seed)
    served, attributed = [], []
    for r in range(n_lists):
        variant = "control" if r % 2 == 0 else "treatment"
        for pos in range(size):
            f = {f"f_{name}": float(rng.random()) for name in FEATURES}
            served.append(
                {"recommendation_id": f"r{r}", "user": f"u{r % 50}", "domain": "song", "surface": "home",
                 "variant_id": variant, "ranker_version": "heuristic-v1", "fallback_level": "NONE",
                 "served_ts": 1_000 * r, "item_id": f"s{rng.integers(0, 300)}", "position": pos,
                 "score": 1.0 / (pos + 1), "reason_code": "TRENDING", "explore": False, "propensity": None,
                 "has_features": True, **f})
            # True relevance depends on semantic/cf, plus position bias in exposure/clicks.
            utility = 2 * f["f_semantic"] + f["f_cf"] - f["f_penalty"] - 0.15 * pos
            base = {"recommendation_id": f"r{r}", "item_id": served[-1]["item_id"], "position": pos,
                    "user": served[-1]["user"], "domain": "song", "variant_id": variant, "explore": False,
                    "served_ts": 1_000 * r, "outcome_ts": 1_000 * r + 5}
            attributed.append({**base, "outcome": "IMPRESSED"})
            if utility > 1.4:
                attributed.append({**base, "outcome": "PLAYED"})
            if utility > 1.9:
                attributed.append({**base, "outcome": "COMPLETED"})
            if utility > 2.3:
                attributed.append({**base, "outcome": "LIKED"})
    return pd.DataFrame(served), pd.DataFrame(attributed)


def test_dataset_grades_and_groups():
    s, a = synthetic_logs(50)
    df = dataset.build(s, a, "song")
    assert set(df["label"].unique()) <= {0, 1, 2, 3}
    assert (df.groupby("recommendation_id")["label"].max() > 0).all()
    train_df, valid_df = dataset.time_split(df)
    assert train_df["served_ts"].max() <= valid_df["served_ts"].min()


def test_train_beats_logged_order_and_registry_round_trip(tmp_path):
    s, a = synthetic_logs()
    df = dataset.build(s, a, "song")
    tr, va = dataset.time_split(df)
    model = train.train_model(tr, va, {**train.PARAMS, "n_estimators": 60})
    rep = train.offline_report(model, va, tr, pd.DataFrame())
    # Logged order is mostly noise w.r.t. true utility here, so the model must win clearly.
    assert rep["model"]["ips_ndcg@5"] > rep["baseline"]["ips_ndcg@5"] + 0.05
    assert set(rep["position_propensity"]) <= set(range(8))
    dump = model.booster_.dump_model()
    assert dump["feature_names"] == MODEL_FEATURES
    registry.save(str(tmp_path), "song", "v1", dump, {"features": MODEL_FEATURES})
    registry.promote(str(tmp_path), "song", "v1")
    assert registry.current(str(tmp_path), "song") == "v1"
    assert json.loads((tmp_path / "ranker/song/v1/metadata.json").read_text())["features"] == MODEL_FEATURES


def test_ab_report_and_srm():
    s, a = synthetic_logs(200)
    r = ab_report.report(s, a, "control", {"song": {"control": 0.5, "treatment": 0.5}})["song"]
    assert r["variants"]["control"]["lists"] == 100
    assert r["srm_p_value"] > 0.05  # balanced split: no sample ratio mismatch
    assert "vs_control" in r["variants"]["treatment"]
    assert ab_report.srm_p_value({"control": 700, "treatment": 300}, {"control": 0.5, "treatment": 0.5}) < 0.001
    # The analysis window excludes everything before `since`.
    late = ab_report.report(s, a, "control", None, since_ms=150_000)["song"]
    assert late["variants"]["control"]["lists"] == 25
    t = ab_report.proportion_test(100, 1000, 150, 1000)
    assert t["p_value"] < 0.01 and t["ci95"][0] > 0
