#!/bin/bash
# G-下：在云上跑 F1 的数据一致性检查（perf/check/check.sh 原样复用，只把 mysql 客户端换成连 RDS 的）。
# 脚本经 S3 传到中间件 EC2 上执行。可选参数：k6 统计的下单成功总数，核对"k6 成功次数 = 库里 901 订单数"（订单不丢）
#   ./perf/cloud/check.sh [k6 成功总数]
set -euo pipefail
cd "$(dirname "$0")/../.."
BUCKET=$(terraform -chdir=infra/env output -raw assets_bucket)
aws s3 cp --quiet perf/check/check.sh "s3://$BUCKET/ops/check.sh"
EXPECT=${1:-}
./perf/cloud/remote.sh "aws s3 cp --quiet s3://$BUCKET/ops/check.sh /tmp/check.sh
export MYSQL_CLI=\"\$MYSQL\"
bash /tmp/check.sh '' 90 || true
N=\$(\$MYSQL -N -e 'SELECT COUNT(*) FROM mh_order.orders WHERE tenant_id = 901')
echo \"库里商家 901 订单数：\$N（k6 下单成功合计：${EXPECT:-未提供}）\""
