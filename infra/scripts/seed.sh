#!/bin/bash
# Load perf/data/seed.sql (merchants 901/902, buyers perf_buyer_*, products / SKUs / inventory) into RDS.
# RDS is in a private subnet, unreachable from a laptop: the middleware EC2 runs it via SSM send-command (no SSH, no session-manager-plugin needed).
# Prerequisite: terraform apply has finished and user-tenant / product-inventory have started once (Flyway created the tables)
#   ./infra/scripts/seed.sh
set -euo pipefail
cd "$(dirname "$0")/../env"
REGION=$(terraform output -raw region 2>/dev/null || echo us-east-1)
IID=$(terraform output -raw middleware_instance_id)
BUCKET=$(terraform output -raw assets_bucket)
RDS=$(terraform output -raw rds_endpoint)

# Runs on the EC2: fetch the password from SSM and the SQL from S3, connect to RDS with the client in the official mysql image (no mysql client installed on the host)
REMOTE="set -e
PW=\$(aws ssm get-parameter --region $REGION --name /merchanthub/mysql-password --with-decryption --query Parameter.Value --output text)
aws s3 cp --region $REGION s3://$BUCKET/ops/seed.sql /tmp/seed.sql
docker run --rm -i -e MYSQL_PWD=\"\$PW\" mysql:8.4 mysql -h $RDS -umhadmin < /tmp/seed.sql
docker run --rm -e MYSQL_PWD=\"\$PW\" mysql:8.4 mysql -h $RDS -umhadmin -t -e 'SELECT tenant_id, sku_id, available FROM mh_product.inventory WHERE tenant_id = 902'
echo SEED_OK"

PARAMS=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.argv[1].split("\n")}))' "$REMOTE")
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript \
  --parameters "$PARAMS" --query Command.CommandId --output text)
echo "Sent (CommandId=${CID}), waiting for it to finish..."
for _ in $(seq 1 30); do
  sleep 4
  STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Pending|InProgress|Delayed) continue ;; esac
  break
done
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" \
  --query '[Status, StandardOutputContent, StandardErrorContent]' --output text
