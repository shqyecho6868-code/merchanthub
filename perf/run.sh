#!/bin/bash
# Run a load test with one command. Uses a local k6 if installed, otherwise the Docker image (nothing to install).
#   ./perf/run.sh <script> [any k6 args]
#   ./perf/run.sh perf/k6/smoke.js
#   ./perf/run.sh perf/k6/scenarios/s1-xxx.js -e VUS=50 -e DURATION=1m
#   BASE_URL=http://<cloud gateway>/api ./perf/run.sh ...      # cloud: only the target address changes
# Results: perf/results/<RUN_ID>/summary.md (per-endpoint QPS / P50 / P99 / error rate) + summary.json
set -e
cd "$(dirname "$0")/.." || exit 1
SCRIPT=$1; shift || true
[ -n "$SCRIPT" ] && [ -f "$SCRIPT" ] || { echo "Usage: $0 <k6 script> [k6 args]"; exit 1; }

K6_IMAGE=${K6_IMAGE:-grafana/k6:1.2.3}
RUN_ID=${RUN_ID:-$(basename "$SCRIPT" .js)-$(date +%Y%m%d-%H%M%S)}
mkdir -p "perf/results/$RUN_ID"

# Variables passed through to k6 (only those that are set)
PASS_VARS="BASE_URL MODE ORDER_URL PAYMENT_URL VUS DURATION RATE RAMP_UP ITERATIONS BUYERS BUYER_PASSWORD BIG_TENANT SMALL_TENANT BIG_SKUS SMALL_SKUS HTTP_TIMEOUT"

if command -v k6 >/dev/null 2>&1; then
  ENV_ARGS=(-e RUN_ID="$RUN_ID")
  for v in $PASS_VARS; do [ -n "${!v}" ] && ENV_ARGS+=(-e "$v=${!v}"); done
  exec k6 run "${ENV_ARGS[@]}" "$@" "$SCRIPT"
fi

# Running in Docker: 127.0.0.1 inside the container is the container itself, so swap local addresses for host.docker.internal
host() { printf %s "$1" | sed 's#//127\.0\.0\.1#//host.docker.internal#; s#//localhost#//host.docker.internal#'; }
BASE_URL=$(host "${BASE_URL:-http://127.0.0.1:10010/api}")
ORDER_URL=$(host "${ORDER_URL:-http://127.0.0.1:8107}")
PAYMENT_URL=$(host "${PAYMENT_URL:-http://127.0.0.1:8108}")
ENV_ARGS=(-e RUN_ID="$RUN_ID" -e RESULT_DIR=/work/perf/results)
for v in $PASS_VARS; do [ -n "${!v}" ] && ENV_ARGS+=(-e "$v=${!v}"); done

exec docker run --rm -i \
  --add-host host.docker.internal:host-gateway \
  -v "$PWD:/work" -w /work \
  "$K6_IMAGE" run "${ENV_ARGS[@]}" "$@" "$SCRIPT"
