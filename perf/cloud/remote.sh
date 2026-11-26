#!/bin/bash
# Run a shell snippet on the middleware EC2 (via SSM send-command, no SSH). RDS is in a private subnet and unreachable
# from a laptop, so all DB queries go through here.
#   ./perf/cloud/remote.sh '<shell command>'      # the command can use $MYSQL (a mysql client preconfigured with the RDS host and password)
set -euo pipefail
cd "$(dirname "$0")/../../infra/env"
REGION=$(terraform output -raw region); IID=$(terraform output -raw middleware_instance_id); RDS=$(terraform output -raw rds_endpoint)
REMOTE="set -e
export PW=\$(aws ssm get-parameter --region $REGION --name /merchanthub/mysql-password --with-decryption --query Parameter.Value --output text)
export MYSQL=\"docker run --rm -i -e MYSQL_PWD=\$PW mysql:8.4 mysql -h $RDS -umhadmin\"
$1"
PARAMS=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.argv[1].split("\n"), "executionTimeout": ["600"]}))' "$REMOTE")
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript \
  --parameters "$PARAMS" --query Command.CommandId --output text)
for _ in $(seq 1 150); do
  sleep 4
  STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Pending|InProgress|Delayed) continue ;; esac
  break
done
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$IID" \
  --query '[StandardOutputContent, StandardErrorContent]' --output text
