"""Offline ML for the recommendation system (Phase 2).

Pipeline: export (Kafka -> Parquet) -> dataset (served x attributed -> graded labels) ->
train (LightGBM LambdaRank, offline eval, promotion gate) -> registry (models/ranker/<domain>/).
The serving path loads promoted models with a pure-Java evaluator; it never imports this code.
"""

# Must equal FeatureExtractor.FEATURES (Java) + "position". The registry refuses mismatches.
FEATURES = ["semantic", "cf", "next", "affinity", "trending", "ctr", "completion", "freshness", "penalty"]
MODEL_FEATURES = FEATURES + ["position"]

# Graded relevance per attributed outcome (max over a served item's outcomes).
OUTCOME_GRADE = {
    "SAVED": 4,
    "LIKED": 3,
    "COMPLETED": 2,
    "PLAYED": 1,
    "IMPRESSED": 0,
    "SKIPPED_EARLY": 0,
    "DISLIKED": 0,
    "OTHER": 0,
}
