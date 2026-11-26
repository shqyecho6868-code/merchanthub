#!/bin/bash
# G-上：把 perf/data/seed.sql（商家 901/902、买家 perf_buyer_*、商品 / SKU / 库存）灌进 RDS。
# RDS 在私有子网，本机连不上：通过 SSM send-command 让中间件 EC2 执行（不需要 SSH、不需要装 session-manager-plugin）。
# 前提：terraform apply 完成，且 user-tenant / product-inventory 已经启动过一次（Flyway 已建表）
#   ./infra/scripts/seed.sh
set -euo pipefail
cd "$(dirname "$0")/../env"
REGION=$(terraform output -raw region 2>/dev/null || echo us-east-1)
IID=$(terraform output -raw middleware_instance_id)
BUCKET=$(terraform output -raw assets_bucket)
RDS=$(terraform output -raw rds_endpoint)

# 在 EC2 上执行：从 SSM 取密码、从 S3 取 SQL，用 mysql 官方镜像里的客户端连 RDS（机器上不装 mysql 客户端）
REMOTE="set -e
PW=\$(aws ssm get-parameter --region $REGION --name /merchanthub/mysql-password --with-decryption --query Parameter.Value --output text)
aws s3 cp --region $REGION s3://$BUCKET/ops/seed.sql /tmp/seed.sql
docker run --rm -i -e MYSQL_PWD=\"\$PW\" mysql:8.4 mysql -h $RDS -umhadmin < /tmp/seed.sql
docker run --rm -e MYSQL_PWD=\"\$PW\" mysql:8.4 mysql -h $RDS -umhadmin -t -e 'SELECT tenant_id, sku_id, available FROM mh_product.inventory WHERE tenant_id = 902'
echo SEED_OK"

PARAMS=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.argv[1].split("\n")}))' "$REMOTE")
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript \
  --parameters "$PARAMS" --query Command.CommandId --output text)
echo "已下发（CommandId=${CID}），等待执行..."
for _ in $(seq 1 30); do
  sleep 4
  STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Pending|InProgress|Delayed) continue ;; esac
  break
done
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" \
  --query '[Status, StandardOutputContent, StandardErrorContent]' --output text
