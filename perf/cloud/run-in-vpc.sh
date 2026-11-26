#!/bin/bash
# G-下：在中间件 EC2 上跑 k6（云内压测），结果拷回本机 perf/results/<RUN_ID>/。
# 为什么不从本机压：G-下实测本机 → us-east-1 的连接在第一轮压测中途被家庭网络 / 运营商防护整段拒绝约 2 分钟
# （连 AWS API 也连不上），从本机压既测不准，还可能把 destroy 用的 CLI 一起堵住。
# 路径：EC2 → NAT → ALB 公网地址 → gateway → order，链路和真实用户一致（ALB 安全组额外放行了 NAT 的 EIP）。
# 代价：k6 和 Nacos / RocketMQ / ES 挤在同一台 2 vCPU 的 EC2 上，压力大时可能抢 CPU（看 EC2 CPU 判断）。
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
echo "云内压测 ${RUN_ID}：$SCRIPT $*"
./perf/cloud/remote.sh "rm -rf /tmp/k6w && mkdir -p /tmp/k6w/perf/results/$RUN_ID && cd /tmp/k6w
aws s3 cp --quiet s3://$BUCKET/ops/k6.tgz - | tar xz 2>/dev/null || true
docker run --rm --user root --ulimit nofile=65535:65535 -v /tmp/k6w:/work -w /work grafana/k6:1.2.3 run -q $ARGS --out csv=perf/results/$RUN_ID/raw.csv.gz $SCRIPT > perf/results/$RUN_ID/k6.log 2>&1 || true
aws s3 cp --quiet --recursive perf/results/$RUN_ID s3://$BUCKET/ops/results/$RUN_ID
tail -3 perf/results/$RUN_ID/k6.log" | tail -5
mkdir -p "perf/results/$RUN_ID"
aws s3 cp --quiet --recursive "s3://$BUCKET/ops/results/$RUN_ID" "perf/results/$RUN_ID"
cat "perf/results/$RUN_ID/summary.md" 2>/dev/null || tail -20 "perf/results/$RUN_ID/k6.log"
