"""Export training/analysis data from Kafka to Parquet (local dir or s3:// via pyarrow).

Reads a snapshot (beginning -> current end offsets) of recs.served.v1, recs.attributed.v1 and the
compacted catalog.items.v1. User ids are pseudonymized with HMAC-SHA256 before anything is written.

TODO(phase-3): per-user HMAC keys (crypto-shredding on deletion) and a Kafka Connect S3 sink.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
from datetime import UTC, datetime
from pathlib import Path

import pandas as pd


def pseudonymize(user_id: str, secret: bytes) -> str:
    return hmac.new(secret, user_id.encode(), hashlib.sha256).hexdigest()[:32]


def _ts(v) -> int:
    """Avro timestamp-millis may arrive as datetime or int."""
    if isinstance(v, datetime):
        return int(v.timestamp() * 1000)
    return int(v)


def served_rows(record: dict, secret: bytes) -> list[dict]:
    rows = []
    for item in record["items"]:
        row = {
            "recommendation_id": record["recommendation_id"],
            "user": pseudonymize(record["user_id"], secret),
            "domain": record["domain"].lower(),
            "surface": record["surface"],
            "variant_id": record["variant_id"],
            "ranker_version": record["ranker_version"],
            "fallback_level": record["fallback_level"],
            "served_ts": _ts(record["served_ts"]),
            "item_id": item["item_id"],
            "position": item["position"],
            "score": item["score"],
            "reason_code": item["reason_code"],
            "explore": item["explore"],
            "propensity": item.get("propensity"),
            "has_features": item.get("features") is not None,
        }
        for name, value in (item.get("features") or {}).items():
            row[f"f_{name}"] = value
        rows.append(row)
    return rows


def attributed_row(record: dict, secret: bytes) -> dict:
    return {
        "recommendation_id": record["recommendation_id"],
        "item_id": record["item_id"],
        "position": record["position"],
        "user": pseudonymize(record["user_id"], secret),
        "domain": (record.get("domain") or "UNKNOWN").lower(),
        "variant_id": record["variant_id"],
        "explore": record["explore"],
        "outcome": record["outcome"],
        "served_ts": _ts(record["served_ts"]),
        "outcome_ts": _ts(record["outcome_ts"]),
    }


def catalog_row(record: dict) -> dict:
    return {
        "item_id": record["item_id"],
        "domain": record["domain"].lower(),
        "creator_id": record["creator_id"],
        "genres": list(record.get("genres") or []),
        "title": record["title"],
        "creator_name": record["creator_name"],
        "moods": list(record.get("mood_tags") or []),
        "description": record.get("description"),
    }


def read_topic(bootstrap: str, registry_url: str, topic: str, max_records: int | None = None):
    """Yields (key, value-dict-or-None) from the beginning to the end offsets at call time."""
    from confluent_kafka import Consumer, TopicPartition
    from confluent_kafka.schema_registry import SchemaRegistryClient
    from confluent_kafka.schema_registry.avro import AvroDeserializer
    from confluent_kafka.serialization import MessageField, SerializationContext

    deser = AvroDeserializer(SchemaRegistryClient({"url": registry_url}))
    consumer = Consumer(
        {"bootstrap.servers": bootstrap, "group.id": "ml-export", "enable.auto.commit": False, "isolation.level": "read_committed"}
    )
    meta = consumer.list_topics(topic, timeout=10).topics[topic]
    parts = [TopicPartition(topic, p, 0) for p in meta.partitions]
    ends = {p.partition: consumer.get_watermark_offsets(p, timeout=10)[1] for p in parts}
    consumer.assign(parts)
    remaining = {p: e for p, e in ends.items() if e > 0}
    n = 0
    while remaining:
        msg = consumer.poll(1.0)
        if msg is not None:
            if msg.error():
                raise RuntimeError(msg.error())
            value = msg.value()
            record = None if value is None else deser(value, SerializationContext(topic, MessageField.VALUE))
            yield (msg.key().decode() if msg.key() else None), record
            n += 1
            if max_records and n >= max_records:
                break
        # Use the consumer position, not message offsets: transactional (EOS) topics end with
        # commit markers the consumer never sees, and compacted topics have offset gaps.
        positions = consumer.position([TopicPartition(topic, p) for p in remaining])
        for tp in positions:
            if tp.offset >= remaining[tp.partition]:
                remaining.pop(tp.partition, None)
    consumer.close()


def write(df: pd.DataFrame, out: str, name: str) -> str:
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S")
    dt = datetime.now(UTC).strftime("%Y-%m-%d")
    target = f"{out.rstrip('/')}/{name}/dt={dt}/part-{stamp}.parquet"
    if not target.startswith("s3://"):
        Path(target).parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(target, index=False)
    return target


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--bootstrap", default=os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092"))
    ap.add_argument("--registry", default=os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:8085"))
    ap.add_argument("--out", default="data")
    args = ap.parse_args(argv)
    secret = os.environ.get("EXPORT_HMAC_SECRET")
    if not secret:
        raise SystemExit("EXPORT_HMAC_SECRET must be set (user ids are pseudonymized before export)")
    key = secret.encode()

    served = [r for _, v in read_topic(args.bootstrap, args.registry, "recs.served.v1") if v for r in served_rows(v, key)]
    attributed = [attributed_row(v, key) for _, v in read_topic(args.bootstrap, args.registry, "recs.attributed.v1") if v]
    latest: dict[str, dict | None] = {}
    for k, v in read_topic(args.bootstrap, args.registry, "catalog.items.v1"):
        latest[k] = v
    catalog = [catalog_row(v) for v in latest.values() if v]
    for name, rows in (("served", served), ("attributed", attributed), ("catalog", catalog)):
        if rows:
            print(f"{name}: {len(rows)} rows -> {write(pd.DataFrame(rows), args.out, name)}")
        else:
            print(f"{name}: no rows")


if __name__ == "__main__":
    main()
