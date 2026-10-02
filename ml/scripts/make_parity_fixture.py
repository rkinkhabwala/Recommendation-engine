"""Train a small LightGBM ranker on synthetic data and write a Java parity fixture:
model.json + metadata.json + expected predictions, used by LightGbmModelTest.java to prove the
pure-Java evaluator scores exactly like LightGBM."""

import json
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from recsys_ml import MODEL_FEATURES

out = Path(sys.argv[1] if len(sys.argv) > 1 else "../services/recommendation-api/src/test/resources/lgbm")
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(3)
n_groups, size = 200, 10
x = pd.DataFrame(rng.random((n_groups * size, len(MODEL_FEATURES))), columns=MODEL_FEATURES)
x.loc[rng.random(len(x)) < 0.05, "cf"] = 0.0  # exercise zero / missing handling paths
y = ((x["semantic"] * 2 + x["cf"] - x["penalty"] + rng.normal(0, 0.2, len(x))) * 2).clip(0, 4).round().astype(int)
model = lgb.LGBMRanker(objective="lambdarank", n_estimators=30, num_leaves=15, min_child_samples=5, verbose=-1)
model.fit(x, y, group=[size] * n_groups)
probe = pd.DataFrame(rng.random((50, len(MODEL_FEATURES))), columns=MODEL_FEATURES)
probe.loc[:4, "position"] = 0.0
(out / "model.json").write_text(json.dumps(model.booster_.dump_model()))
(out / "metadata.json").write_text(json.dumps({"version": "vtest", "features": MODEL_FEATURES}))
(out / "probe.json").write_text(
    json.dumps({"inputs": probe.values.tolist(), "expected": model.booster_.predict(probe, raw_score=True).tolist()})
)
print(f"wrote fixture to {out}")
