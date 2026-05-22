#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="raft"
OUT_DIR="${OUT_DIR:-logs}"

mkdir -p "${OUT_DIR}"

echo "[logs] Writing logs to ${OUT_DIR}/"
echo "[logs] Ctrl-C to stop tailing."

kubectl -n "${NAMESPACE}" logs -f -l app=raft --prefix --timestamps | tee "${OUT_DIR}/raft-nodes.log" &
NODES_PID=$!

kubectl -n "${NAMESPACE}" logs -f deploy/raft-client --prefix --timestamps | tee "${OUT_DIR}/raft-client.log" &
CLIENT_PID=$!

trap 'kill ${NODES_PID} ${CLIENT_PID} 2>/dev/null || true' INT TERM EXIT
wait
