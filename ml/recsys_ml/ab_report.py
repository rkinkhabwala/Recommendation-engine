"""A/B report per domain and variant: CTR, completion, early-skip and like rates with 95% CIs,
two-proportion z-tests against control, and a sample-ratio-mismatch (SRM) check.

The unit of randomization is the user, so SRM counts distinct users per variant. Analyse only the
experiment window (`--since`): mixing periods (e.g. before a variant existed) produces spurious,
Simpson's-paradox differences — which the SRM check exists to catch.

Rates are computed per impression/play; outcomes of one user are correlated, so these CIs are
optimistic. TODO(phase-3): user-clustered (delta-method) CIs, CUPED, sequential testing.
"""

from __future__ import annotations

import argparse
import json
import math

import pandas as pd

from recsys_ml import dataset


def proportion_test(x1: int, n1: int, x2: int, n2: int) -> dict:
    """Difference p2 - p1 with 95% CI and two-sided p-value (normal approximation)."""
    if n1 == 0 or n2 == 0:
        return {"diff": None, "ci95": None, "p_value": None}
    p1, p2 = x1 / n1, x2 / n2
    pooled = (x1 + x2) / (n1 + n2)
    se_pooled = math.sqrt(max(pooled * (1 - pooled) * (1 / n1 + 1 / n2), 1e-12))
    z = (p2 - p1) / se_pooled
    p_value = math.erfc(abs(z) / math.sqrt(2))
    se = math.sqrt(max(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2, 1e-12))
    return {"diff": p2 - p1, "ci95": [p2 - p1 - 1.96 * se, p2 - p1 + 1.96 * se], "p_value": p_value}


def srm_p_value(counts: dict[str, int], expected_share: dict[str, float]) -> float:
    """Chi-square goodness of fit of assignment counts vs the configured split (Wilson–Hilferty)."""
    total = sum(counts.values())
    chi2 = sum((counts.get(v, 0) - total * s) ** 2 / (total * s) for v, s in expected_share.items() if s > 0)
    k = max(len(expected_share) - 1, 1)
    z = ((chi2 / k) ** (1 / 3) - (1 - 2 / (9 * k))) / math.sqrt(2 / (9 * k))
    return 0.5 * math.erfc(z / math.sqrt(2))


def variant_stats(served: pd.DataFrame, attributed: pd.DataFrame) -> pd.DataFrame:
    lists = served.groupby(["domain", "variant_id"])["recommendation_id"].nunique().rename("lists")
    users = served.groupby(["domain", "variant_id"])["user"].nunique().rename("users")
    a = attributed.drop_duplicates(["recommendation_id", "item_id", "outcome"])
    counts = a.pivot_table(index=["domain", "variant_id"], columns="outcome", values="item_id", aggfunc="count", fill_value=0)
    df = pd.concat([lists, users, counts], axis=1).fillna(0)
    for col in ("IMPRESSED", "PLAYED", "COMPLETED", "SKIPPED_EARLY", "LIKED", "SAVED"):
        if col not in df:
            df[col] = 0
    return df.astype(int)


def report(
    served: pd.DataFrame,
    attributed: pd.DataFrame,
    control: str,
    split: dict[str, dict[str, float]] | None,
    since_ms: int | None = None,
) -> dict:
    """`split`: domain -> variant -> expected share (SRM is skipped for domains without one)."""
    if since_ms is not None:
        served = served[served["served_ts"] >= since_ms]
        attributed = attributed[attributed["served_ts"] >= since_ms]
    stats = variant_stats(served, attributed)
    out = {}
    for domain, g in stats.groupby(level=0):
        g = g.droplevel(0)
        variants = {}
        for v, row in g.iterrows():
            imp, played = int(row["IMPRESSED"]), int(row["PLAYED"])
            variants[v] = {
                "users": int(row["users"]),
                "lists": int(row["lists"]),
                "impressions": imp,
                "ctr": played / imp if imp else None,
                "completion_rate": row["COMPLETED"] / played if played else None,
                "early_skip_rate": row["SKIPPED_EARLY"] / played if played else None,
                "like_rate": (row["LIKED"] + row["SAVED"]) / imp if imp else None,
            }
        if control in g.index:
            c = g.loc[control]
            for v, row in g.iterrows():
                if v == control:
                    continue
                variants[v]["vs_control"] = {
                    "ctr": proportion_test(int(c["PLAYED"]), int(c["IMPRESSED"]), int(row["PLAYED"]), int(row["IMPRESSED"])),
                    "completion_rate": proportion_test(int(c["COMPLETED"]), int(c["PLAYED"]), int(row["COMPLETED"]), int(row["PLAYED"])),
                    "early_skip_rate": proportion_test(int(c["SKIPPED_EARLY"]), int(c["PLAYED"]), int(row["SKIPPED_EARLY"]), int(row["PLAYED"])),
                }
        expected = (split or {}).get(domain)
        out_srm = None
        if expected and set(g.index) <= set(expected):
            total = sum(expected.values())
            counts = {v: int(r["users"]) for v, r in g.iterrows()}
            out_srm = srm_p_value(counts, {v: expected[v] / total for v in expected})
        out[domain] = {"variants": variants, "srm_p_value": out_srm}
    return out


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", default="data")
    ap.add_argument("--control", default="control")
    ap.add_argument("--split", help="expected split per domain, e.g. song:control=0.5,song:lgbm_thompson=0.5")
    ap.add_argument("--since", help="analysis window start, ISO-8601 (experiment start)")
    args = ap.parse_args(argv)
    split: dict[str, dict[str, float]] = {}
    for part in (args.split or "").split(","):
        if part:
            key, share = part.split("=")
            domain, variant = key.split(":")
            split.setdefault(domain, {})[variant] = float(share)
    since = int(pd.Timestamp(args.since).timestamp() * 1000) if args.since else None
    result = report(dataset.load(args.data, "served"), dataset.load(args.data, "attributed"), args.control, split, since)
    print(json.dumps(result, indent=2, default=float))


if __name__ == "__main__":
    main()
