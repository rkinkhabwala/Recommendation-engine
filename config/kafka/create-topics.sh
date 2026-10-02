#!/usr/bin/env bash
# Idempotently creates topics from topics.conf. Usage: create-topics.sh <bootstrap> <conf>
set -euo pipefail
BOOTSTRAP="${1:-kafka:29092}"
CONF="${2:-/config/topics.conf}"
RF="${REPLICATION_FACTOR:-1}"
grep -vE '^\s*(#|$)' "$CONF" | while read -r name partitions cleanup configs; do
  args=(--bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$name"
        --partitions "$partitions" --replication-factor "$RF" --config "cleanup.policy=$cleanup")
  IFS=',' read -ra kvs <<< "${configs:-}"
  for kv in "${kvs[@]}"; do [[ -n "$kv" ]] && args+=(--config "$kv"); done
  kafka-topics "${args[@]}"
done
echo "Topics ready:"
kafka-topics --bootstrap-server "$BOOTSTRAP" --list
