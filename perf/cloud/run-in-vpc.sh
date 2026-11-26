#!/bin/bash
# Run k6 on the middleware EC2 (in-VPC load test) and copy results back to perf/results/<RUN_ID>/.
# Why not from a laptop: in practice, the laptop -> us-east-1 connection was blocked for ~2 minutes mid-test by the
# home network / ISP protection (even the AWS API was unreachable), so results were unreliable and it could also block the CLI needed for destroy.
# Path: EC2 -> NAT -> ALB public address -> gateway -> order, same as real users (the ALB security group also allows the NAT EIP).
# Trade-off: k6 shares a 2 vCPU EC2 with Nacos / RocketMQ / ES and may compete for CPU under heavy load (watch the EC2 CPU).
#   RUN_ID=c1-spread ./perf/cloud/run-in-vpc.sh perf/k6/scenarios/s2a-hot-sku.js -e HOT=0 -e STAGES=50,100,200,300
set -euo pipefail
cd "$(dirname "$0")/../.."
SCRIPT=$1; shift
RUN_ID=${RUN_ID:-$(basename "$SCRIPT" .js)-vpc-$(date +%H%M%S)}
BUCKET=$(terraform -chdir=infra/env output -raw assets_bucket)
ALB=$(terraform -chdir=infra/env output -raw alb_url)
COPYFILE_DISABLE=1 tar --no-xattrs -czf /tmp/mh-k6.tgz perf/k6
aws s3 cp --quiet /tmp/mh-k6.tgz "s3://$BUCKET/ops/k6.tgz"
ARGS="-e RUN_ID=$RUN_ID -e RESULT_DIR=/work/perf/results -e BASE_URL=${ALB%/}/api $*"
echo "In-VPC load test ${RUN_ID}: $SCRIPT $*"
./perf/cloud/remote.sh "rm -rf /tmp/k6w && mkdir -p /tmp/k6w/perf/results/$RUN_ID && cd /tmp/k6w
aws s3 cp --quiet s3://$BUCKET/ops/k6.tgz - | tar xz 2>/dev/null || true
docker run --rm --user root --ulimit nofile=65535:65535 -v /tmp/k6w:/work -w /work grafana/k6:1.2.3 run -q $ARGS --out csv=perf/results/$RUN_ID/raw.csv.gz $SCRIPT > perf/results/$RUN_ID/k6.log 2>&1 || true
aws s3 cp --quiet --recursive perf/results/$RUN_ID s3://$BUCKET/ops/results/$RUN_ID
tail -3 perf/results/$RUN_ID/k6.log" | tail -5
mkdir -p "perf/results/$RUN_ID"
aws s3 cp --quiet --recursive "s3://$BUCKET/ops/results/$RUN_ID" "perf/results/$RUN_ID"
cat "perf/results/$RUN_ID/summary.md" 2>/dev/null || tail -20 "perf/results/$RUN_ID/k6.log"
