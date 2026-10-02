"""Offline ranking metrics: precision@K, recall@K, NDCG@K, coverage, diversity, novelty."""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence

import numpy as np
import pandas as pd


def dcg(labels: Sequence[float], k: int, weights: Sequence[float] | None = None) -> float:
    w = weights if weights is not None else [1.0] * len(labels)
    return sum(wi * (2**rel - 1) / math.log2(i + 2) for i, (rel, wi) in enumerate(zip(labels[:k], w[:k])))


def ndcg_at_k(labels_in_ranked_order: Sequence[float], k: int, weights: Sequence[float] | None = None) -> float:
    """NDCG; with `weights` (inverse examination propensities of where each label was observed)
    gains are IPS-weighted, which removes most of the logging policy's position advantage."""
    w = list(weights) if weights is not None else [1.0] * len(labels_in_ranked_order)
    pairs = sorted(zip(labels_in_ranked_order, w), key=lambda p: (2 ** p[0] - 1) * p[1], reverse=True)
    ideal = dcg([p[0] for p in pairs], k, [p[1] for p in pairs])
    return 0.0 if ideal == 0 else dcg(labels_in_ranked_order, k, w) / ideal


def precision_at_k(labels_in_ranked_order: Sequence[float], k: int) -> float:
    top = labels_in_ranked_order[:k]
    return 0.0 if not top else sum(1 for r in top if r > 0) / k


def recall_at_k(labels_in_ranked_order: Sequence[float], k: int) -> float:
    relevant = sum(1 for r in labels_in_ranked_order if r > 0)
    return 0.0 if relevant == 0 else sum(1 for r in labels_in_ranked_order[:k] if r > 0) / relevant


def position_propensities(df: pd.DataFrame, max_weight: float = 10.0) -> dict[int, float]:
    """Examination propensity per position, estimated as positive-rate(pos) / positive-rate(0)
    (position-based model). Confounded by relevance, so it under-corrects — a conservative first
    cut. Clipped so no weight exceeds `max_weight` (variance control)."""
    rates = df.assign(pos=df["label"] > 0).groupby("position")["pos"].mean()
    top = rates.get(0, rates.max()) or 1.0
    return {int(p): max(min(r / top, 1.0), 1.0 / max_weight) for p, r in rates.items()}


def evaluate(df: pd.DataFrame, score_col: str, k: int = 5, ascending: bool = False, propensity: dict[int, float] | None = None) -> dict:
    """Mean per-group metrics after ordering each group by score_col. With `propensity`, also
    reports IPS-weighted NDCG."""
    ndcgs, ips, p1, p3, recalls = [], [], [], [], []
    for _, g in df.groupby("recommendation_id", sort=False):
        g = g.sort_values(score_col, ascending=ascending)
        ranked = g["label"].tolist()
        ndcgs.append(ndcg_at_k(ranked, k))
        if propensity is not None:
            w = [1.0 / propensity.get(int(p), min(propensity.values())) for p in g["position"]]
            ips.append(ndcg_at_k(ranked, k, w))
        p1.append(precision_at_k(ranked, 1))
        p3.append(precision_at_k(ranked, 3))
        recalls.append(recall_at_k(ranked, 3))
    out = {
        f"ndcg@{k}": float(np.mean(ndcgs)) if ndcgs else 0.0,
        "precision@1": float(np.mean(p1)) if p1 else 0.0,
        "precision@3": float(np.mean(p3)) if p3 else 0.0,
        "recall@3": float(np.mean(recalls)) if recalls else 0.0,
        "groups": len(ndcgs),
    }
    if propensity is not None:
        out[f"ips_ndcg@{k}"] = float(np.mean(ips)) if ips else 0.0
    return out


def auc(scores: Sequence[float], targets: Sequence[int]) -> float:
    """ROC AUC via the rank-sum (Mann–Whitney) statistic; 0.5 if a class is missing."""
    s = np.asarray(scores, dtype=float)
    t = np.asarray(targets, dtype=int)
    pos, neg = int(t.sum()), int(len(t) - t.sum())
    if pos == 0 or neg == 0:
        return 0.5
    ranks = pd.Series(s).rank(method="average").to_numpy()
    return float((ranks[t == 1].sum() - pos * (pos + 1) / 2) / (pos * neg))


def top_k_lists(df: pd.DataFrame, score_col: str, k: int, ascending: bool = False) -> list[list[str]]:
    return [
        g.sort_values(score_col, ascending=ascending)["item_id"].head(k).tolist()
        for _, g in df.groupby("recommendation_id", sort=False)
    ]


def coverage(lists: Iterable[Sequence[str]], catalog_size: int) -> float:
    seen = {i for lst in lists for i in lst}
    return 0.0 if catalog_size == 0 else len(seen) / catalog_size


def intra_list_diversity(lists: Iterable[Sequence[str]], creator_of: dict[str, str]) -> float:
    """Mean share of item pairs in a list that have different creators (1 = all different)."""
    values = []
    for lst in lists:
        creators = [creator_of.get(i) for i in lst]
        pairs = [(a, b) for i, a in enumerate(creators) for b in creators[i + 1 :]]
        if pairs:
            values.append(sum(1 for a, b in pairs if a != b or a is None) / len(pairs))
    return float(np.mean(values)) if values else 0.0


def novelty(lists: Iterable[Sequence[str]], popularity: dict[str, float]) -> float:
    """Mean self-information -log2(p(item)) of recommended items (higher = less mainstream)."""
    total = sum(popularity.values()) or 1.0
    vals = [-math.log2(max(popularity.get(i, 0.5), 0.5) / total) for lst in lists for i in lst]
    return float(np.mean(vals)) if vals else 0.0
