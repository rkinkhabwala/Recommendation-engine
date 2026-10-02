"""Benchmark embedding dimensions (e.g. 512 vs 1536) on next-item recall@K.

For consecutive positive engagements a -> b of the same user in the same domain (from attributed
outcomes), embed the catalog at each dimension and measure whether b is among a's K nearest
neighbours. Requires OPENAI_API_KEY (calls the embeddings API with the `dimensions` parameter);
cost is reported up front from a token estimate. Run with --max-items to bound cost.
"""

from __future__ import annotations

import argparse
import os

import numpy as np
import pandas as pd
import requests

from recsys_ml import dataset

POSITIVE = {"PLAYED", "COMPLETED", "LIKED", "SAVED"}


def item_text(row) -> str:
    parts = [f"{row['title']} by {row['creator_name']}.", "Genres: " + ", ".join(row["genres"]) + "."]
    if len(row["moods"]):
        parts.append("Mood: " + ", ".join(row["moods"]) + ".")
    if isinstance(row.get("description"), str):
        parts.append(row["description"][:1500])
    return " ".join(parts)


def pairs(attributed: pd.DataFrame) -> pd.DataFrame:
    a = attributed[attributed["outcome"].isin(POSITIVE)].sort_values("outcome_ts")
    a = a.drop_duplicates(["user", "domain", "item_id"])
    a["next_item"] = a.groupby(["user", "domain"])["item_id"].shift(-1)
    return a.dropna(subset=["next_item"])[["domain", "item_id", "next_item"]]


def recall_at_k(vectors: dict[str, np.ndarray], pair_df: pd.DataFrame, domain_of: dict[str, str], k: int) -> float:
    ids = list(vectors)
    matrix = np.stack([vectors[i] for i in ids])
    index = {i: n for n, i in enumerate(ids)}
    domains = np.array([domain_of[i] for i in ids])
    hits = total = 0
    for _, p in pair_df.iterrows():
        if p.item_id not in index or p.next_item not in index:
            continue
        sims = matrix @ matrix[index[p.item_id]]
        sims[index[p.item_id]] = -np.inf
        sims[domains != p.domain] = -np.inf
        top = np.argpartition(-sims, k)[:k]
        hits += int(index[p.next_item] in set(top))
        total += 1
    return hits / total if total else 0.0


def embed(texts: list[str], model: str, dims: int, key: str, batch: int = 256) -> np.ndarray:
    out = []
    for i in range(0, len(texts), batch):
        r = requests.post(
            "https://api.openai.com/v1/embeddings",
            headers={"Authorization": f"Bearer {key}"},
            json={"model": model, "input": texts[i : i + batch], "dimensions": dims},
            timeout=60,
        )
        r.raise_for_status()
        out.extend(d["embedding"] for d in sorted(r.json()["data"], key=lambda d: d["index"]))
    m = np.array(out, dtype=np.float32)
    return m / np.linalg.norm(m, axis=1, keepdims=True)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", default="data")
    ap.add_argument("--model", default=os.environ.get("RECS_EMBEDDING_MODEL", "text-embedding-3-small"))
    ap.add_argument("--dims", default="512,1536")
    ap.add_argument("--k", type=int, default=20)
    ap.add_argument("--max-items", type=int, default=5000)
    args = ap.parse_args(argv)
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise SystemExit("OPENAI_API_KEY is required (this benchmark calls the embeddings API)")
    catalog = dataset.load(args.data, "catalog")
    p = pairs(dataset.load(args.data, "attributed"))
    needed = set(p["item_id"]) | set(p["next_item"])
    sample = pd.concat([catalog[catalog["item_id"].isin(needed)], catalog[~catalog["item_id"].isin(needed)]])
    sample = sample.head(args.max_items)
    texts = [item_text(r) for _, r in sample.iterrows()]
    print(f"items={len(sample)} pairs={len(p)} est_tokens={sum(len(t) for t in texts) // 4} per dimension setting")
    domain_of = dict(zip(sample["item_id"], sample["domain"]))
    for dims in map(int, args.dims.split(",")):
        m = embed(texts, args.model, dims, key)
        vectors = dict(zip(sample["item_id"], m))
        print(f"dims={dims} recall@{args.k}={recall_at_k(vectors, p, domain_of, args.k):.4f} memory_per_5M_items_gb={5e6 * dims * 4 / 1e9:.1f}")


if __name__ == "__main__":
    main()
