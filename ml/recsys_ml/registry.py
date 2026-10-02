"""File-based model registry shared with the serving path (RankerRegistry.java).

    <root>/ranker/<domain>/<version>/model.json      LightGBM dump_model()
    <root>/ranker/<domain>/<version>/metadata.json   features, metrics, data window, params
    <root>/ranker/<domain>/candidate                 passed the offline gate: eligible for A/B
    <root>/ranker/<domain>/current                   promoted (after an A/B win or the IPS gate)

Works on a local path or a mounted volume; S3 would use the same layout. TODO(phase-3): object
store + model approvals.
"""

from __future__ import annotations

import json
import os
from pathlib import Path


def version_dir(root: str, domain: str, version: str) -> Path:
    return Path(root) / "ranker" / domain / version


def save(root: str, domain: str, version: str, model_json: dict, metadata: dict) -> Path:
    d = version_dir(root, domain, version)
    d.mkdir(parents=True, exist_ok=False)
    (d / "model.json").write_text(json.dumps(model_json))
    (d / "metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True))
    return d


def promote(root: str, domain: str, version: str, pointer_name: str = "current") -> None:
    pointer = Path(root) / "ranker" / domain / pointer_name
    tmp = pointer.with_suffix(".tmp")
    tmp.write_text(version + "\n")
    os.replace(tmp, pointer)  # atomic: readers see the old or the new version, never half


def current(root: str, domain: str, pointer_name: str = "current") -> str | None:
    pointer = Path(root) / "ranker" / domain / pointer_name
    return pointer.read_text().strip() if pointer.exists() else None


def rollback(root: str, domain: str, version: str) -> None:
    if not version_dir(root, domain, version).exists():
        raise SystemExit(f"no such version {version}")
    promote(root, domain, version)


def main(argv: list[str] | None = None) -> None:
    """recsys-promote --domain song --version v... [--pointer current]: promote after an A/B win."""
    import argparse

    ap = argparse.ArgumentParser(description=main.__doc__)
    ap.add_argument("--domain", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--models", default="models")
    ap.add_argument("--pointer", default="current", choices=["current", "candidate"])
    args = ap.parse_args(argv)
    if not version_dir(args.models, args.domain, args.version).exists():
        raise SystemExit(f"no such version {args.version}")
    promote(args.models, args.domain, args.version, args.pointer)
    print(f"{args.domain}: {args.pointer} -> {args.version}")


if __name__ == "__main__":
    main()
