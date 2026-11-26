#!/bin/bash
# Load-test the cloud environment. Reuses the local k6 scripts with the target switched to the ALB domain; also writes a per-request CSV for timeline analysis.
#   ./perf/cloud/run.sh <k6 script> [k6 args]
#   RUN_ID=c1-spread ./perf/cloud/run.sh perf/k6/scenarios/s2a-hot-sku.js -e HOT=0 -e STAGES=100,200,300,400
# Load generator = this machine (over the internet to us-east-1); measure RTT first and save it with the results (internet latency adds directly to P50/P99)
set -e
cd "$(dirname "$0")/../.." || exit 1
ALB=$(terraform -chdir=infra/env output -raw alb_url)
export BASE_URL="${ALB%/}/api"
export RUN_ID=${RUN_ID:-$(basename "$1" .js)-cloud-$(date +%H%M%S)}
mkdir -p "perf/results/$RUN_ID"
# Internet RTT: hit a non-business address 10 times (the ALB returns 404 for /actuator directly, no task involved)
for _ in $(seq 1 10); do curl -s -o /dev/null -w '%{time_connect}\n' "$ALB/actuator/health"; done \
  | sort -n | awk '{a[NR]=$1} END {printf "TCP connect (~1 RTT) median %.1f ms\n", a[int((NR+1)/2)]*1000}' \
  | tee "perf/results/$RUN_ID/rtt.txt"
echo "Target $BASE_URL, results in perf/results/$RUN_ID/"
./perf/run.sh "$@" --out "csv=perf/results/$RUN_ID/raw.csv.gz"
