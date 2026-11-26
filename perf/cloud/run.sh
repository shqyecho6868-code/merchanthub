#!/bin/bash
# G-下：对云上环境压测。复用 F1 的 k6 脚本，只把目标地址换成 ALB 域名；额外输出逐请求 CSV 供时间线分析。
#   ./perf/cloud/run.sh <k6 脚本> [k6 参数]
#   RUN_ID=c1-spread ./perf/cloud/run.sh perf/k6/scenarios/s2a-hot-sku.js -e HOT=0 -e STAGES=100,200,300,400
# 压测机 = 本机（经公网到 us-east-1），压之前先测一次 RTT 记到结果目录（公网延迟会直接加到 P50/P99 上）
set -e
cd "$(dirname "$0")/../.." || exit 1
ALB=$(terraform -chdir=infra/env output -raw alb_url)
export BASE_URL="${ALB%/}/api"
export RUN_ID=${RUN_ID:-$(basename "$1" .js)-cloud-$(date +%H%M%S)}
mkdir -p "perf/results/$RUN_ID"
# 公网 RTT：打 10 次一个不进业务的地址（网关自己的 /actuator 被 ALB 直接 404，不经过任何 task）
for _ in $(seq 1 10); do curl -s -o /dev/null -w '%{time_connect}\n' "$ALB/actuator/health"; done \
  | sort -n | awk '{a[NR]=$1} END {printf "TCP 建连（≈1 个 RTT）中位数 %.1f ms\n", a[int((NR+1)/2)]*1000}' \
  | tee "perf/results/$RUN_ID/rtt.txt"
echo "目标 $BASE_URL，结果 perf/results/$RUN_ID/"
./perf/run.sh "$@" --out "csv=perf/results/$RUN_ID/raw.csv.gz"
