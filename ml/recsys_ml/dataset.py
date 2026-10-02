"""Learning-to-rank dataset: served items (with logged serving-time features) x attributed outcomes."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from recsys_ml import FEATURES, OUTCOME_GRADE

VISIBLE_SLOTS_FALLBACK = 5


def load(data_dir: str, name: str) -> pd.DataFrame:
    path = Path(data_dir) / name
    files = sorted(path.rglob("*.parquet")) if path.exists() else []
    if not files:
        return pd.DataFrame()
    return pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)


def build(served: pd.DataFrame, attributed: pd.DataFrame, domain: str) -> pd.DataFrame:
    """One row per exposed served item with features, graded label and query group.

    Exposure: the item got an IMPRESSED (or any) outcome. If impressions were not logged, the first
    VISIBLE_SLOTS_FALLBACK slots are assumed visible. Groups without any positive are dropped (they
    carry no ranking signal).
    """
    if served.empty:
        return pd.DataFrame()
    s = served[(served["domain"] == domain) & served["has_features"]].copy()
    s = s.drop_duplicates(["recommendation_id", "item_id"])
    feature_cols = [f"f_{f}" for f in FEATURES]
    missing = [c for c in feature_cols if c not in s.columns]
    if missing:
        raise ValueError(f"served data lacks feature columns {missing}")
    if attributed.empty:
        labels = pd.DataFrame(columns=["recommendation_id", "item_id", "label", "exposed"])
    else:
        a = attributed[attributed["domain"] == domain].copy()
        a["grade"] = a["outcome"].map(OUTCOME_GRADE).fillna(0).astype(int)
        labels = (
            a.groupby(["recommendation_id", "item_id"])
            .agg(label=("grade", "max"))
            .reset_index()
            .assign(exposed=True)
        )
    df = s.merge(labels, on=["recommendation_id", "item_id"], how="left")
    impressions_logged = not attributed.empty and (attributed["outcome"] == "IMPRESSED").any()
    if impressions_logged:
        df = df[df["exposed"].fillna(False).astype(bool)]
    else:
        df = df[df["position"] < VISIBLE_SLOTS_FALLBACK]
    df["label"] = df["label"].fillna(0).astype(int)
    positive_groups = df.groupby("recommendation_id")["label"].transform("max") > 0
    sizes = df.groupby("recommendation_id")["item_id"].transform("count")
    df = df[positive_groups & (sizes >= 2)]
    df = df.sort_values(["served_ts", "recommendation_id", "position"]).reset_index(drop=True)
    return df


def time_split(df: pd.DataFrame, valid_fraction: float = 0.2) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Split by served time at group granularity (no group straddles the split, no leakage)."""
    groups = df.groupby("recommendation_id")["served_ts"].min().sort_values()
    cut = int(len(groups) * (1 - valid_fraction))
    train_ids = set(groups.index[:cut])
    return df[df["recommendation_id"].isin(train_ids)], df[~df["recommendation_id"].isin(train_ids)]
