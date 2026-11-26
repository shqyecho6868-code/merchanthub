#!/bin/bash
# Run the data consistency check against the cloud (reuses perf/check/check.sh as is; only the mysql client is swapped for one connected to RDS).
# The script is shipped via S3 and runs on the middleware EC2. Optional arg: k6's total successful orders, to verify
# "k6 successes = merchant 901 orders in the DB" (no orders lost)
#   ./perf/cloud/check.sh [k6 successful orders]
set -euo pipefail
cd "$(dirname "$0")/../.."
BUCKET=$(terraform -chdir=infra/env output -raw assets_bucket)
aws s3 cp --quiet perf/check/check.sh "s3://$BUCKET/ops/check.sh"
EXPECT=${1:-}
./perf/cloud/remote.sh "aws s3 cp --quiet s3://$BUCKET/ops/check.sh /tmp/check.sh
export MYSQL_CLI=\"\$MYSQL\"
bash /tmp/check.sh '' 90 || true
N=\$(\$MYSQL -N -e 'SELECT COUNT(*) FROM mh_order.orders WHERE tenant_id = 901')
echo \"Merchant 901 orders in DB: \$N (k6 successful orders: ${EXPECT:-not given})\""
