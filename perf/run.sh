#!/bin/bash
# F1：一条命令跑压测。本机装了 k6 就用本机的，否则用 Docker 镜像（不需要安装任何东西）。
#   ./perf/run.sh <脚本> [任意 k6 参数]
#   ./perf/run.sh perf/k6/smoke.js
#   ./perf/run.sh perf/k6/scenarios/s1-xxx.js -e VUS=50 -e DURATION=1m
#   BASE_URL=http://<云上网关>/api ./perf/run.sh ...      # G 阶段：只换目标地址
# 结果：perf/results/<RUN_ID>/summary.md（按接口的 QPS / P50 / P99 / 错误率）+ summary.json
set -e
cd "$(dirname "$0")/.." || exit 1
SCRIPT=$1; shift || true
[ -n "$SCRIPT" ] && [ -f "$SCRIPT" ] || { echo "用法：$0 <k6 脚本> [k6 参数]"; exit 1; }

K6_IMAGE=${K6_IMAGE:-grafana/k6:1.2.3}
RUN_ID=${RUN_ID:-$(basename "$SCRIPT" .js)-$(date +%Y%m%d-%H%M%S)}
mkdir -p "perf/results/$RUN_ID"

# 透传给 k6 的变量（只传设置了的）
PASS_VARS="BASE_URL MODE ORDER_URL PAYMENT_URL VUS DURATION RATE RAMP_UP ITERATIONS BUYERS BUYER_PASSWORD BIG_TENANT SMALL_TENANT BIG_SKUS SMALL_SKUS HTTP_TIMEOUT"

if command -v k6 >/dev/null 2>&1; then
  ENV_ARGS=(-e RUN_ID="$RUN_ID")
  for v in $PASS_VARS; do [ -n "${!v}" ] && ENV_ARGS+=(-e "$v=${!v}"); done
  exec k6 run "${ENV_ARGS[@]}" "$@" "$SCRIPT"
fi

# Docker 里跑：容器内的 127.0.0.1 是容器自己，要把默认的本机地址换成 host.docker.internal
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
