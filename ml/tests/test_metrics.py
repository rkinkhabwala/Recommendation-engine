import math

import pandas as pd

from recsys_ml import metrics


def test_ndcg_perfect_and_reversed():
    assert metrics.ndcg_at_k([3, 2, 0], 3) == 1.0
    worst = metrics.ndcg_at_k([0, 2, 3], 3)
    assert 0 < worst < 1


def test_precision_recall():
    assert metrics.precision_at_k([1, 0, 1, 0, 0], 5) == 0.4
    assert metrics.recall_at_k([1, 0, 1, 0, 0, 1], 5) == 2 / 3


def test_evaluate_orders_by_score():
    df = pd.DataFrame(
        {"recommendation_id": ["r"] * 3, "label": [0, 3, 1], "good": [0.1, 0.9, 0.5], "bad": [0.9, 0.1, 0.5]}
    )
    assert metrics.evaluate(df, "good", k=3)["ndcg@3"] == 1.0
    assert metrics.evaluate(df, "bad", k=3)["ndcg@3"] < 1.0


def test_ips_weights_upweight_positives_seen_at_low_positions():
    # A positive observed at position 2 (rarely examined) counts more than one seen at position 0.
    plain = metrics.ndcg_at_k([0, 0, 1], 3)
    weighted = metrics.ndcg_at_k([0, 0, 1], 3, weights=[1, 1, 5])
    assert weighted == plain  # single positive: normalization cancels the weight
    two = metrics.ndcg_at_k([1, 0, 1], 3, weights=[1, 1, 5])
    assert two < metrics.ndcg_at_k([1, 0, 1], 3)  # the heavier positive is ranked low
    df = pd.DataFrame({"position": [0, 1, 0, 1], "label": [1, 0, 1, 1]})
    prop = metrics.position_propensities(df)
    assert prop[0] == 1.0 and prop[1] == 0.5


def test_coverage_diversity_novelty():
    lists = [["a", "b"], ["a", "c"]]
    assert metrics.coverage(lists, 10) == 0.3
    assert metrics.intra_list_diversity([["a", "b"]], {"a": "x", "b": "x"}) == 0.0
    assert metrics.intra_list_diversity([["a", "b"]], {"a": "x", "b": "y"}) == 1.0
    pop = {"a": 100, "b": 1}
    assert metrics.novelty([["b"]], pop) > metrics.novelty([["a"]], pop)
    assert not math.isnan(metrics.novelty([["zzz"]], pop))


def test_auc():
    assert metrics.auc([0.1, 0.4, 0.35, 0.8], [0, 0, 1, 1]) == 0.75
    assert metrics.auc([1, 2], [1, 1]) == 0.5
