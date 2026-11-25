#!/bin/bash
# G-上：一眼看云上环境起没起来。
#   ./infra/scripts/status.sh
# 期望：6 个服务 running=1；ALB 两个目标组 healthy；中间件 EC2 日志最后一行是 MIDDLEWARE_READY
set -uo pipefail
cd "$(dirname "$0")/../env"
REGION=us-east-1
CLUSTER=merchanthub

echo "== ECS 服务（desired / running / 最近一条事件）"
aws ecs describe-services --region $REGION --cluster $CLUSTER \
  --services gateway user-tenant product-inventory order payment realtime \
  --query 'services[].[serviceName, desiredCount, runningCount, events[0].message]' --output text

echo; echo "== ALB 目标健康"
for TG in $(aws elbv2 describe-target-groups --region $REGION --names merchanthub-gateway merchanthub-realtime \
    --query 'TargetGroups[].TargetGroupArn' --output text); do
  echo "${TG##*:targetgroup/}"
  aws elbv2 describe-target-health --region $REGION --target-group-arn "$TG" \
    --query 'TargetHealthDescriptions[].[Target.Id, TargetHealth.State, TargetHealth.Reason]' --output text
done

echo; echo "== 中间件 EC2 启动脚本（最后 5 行）"
IID=$(terraform output -raw middleware_instance_id)
aws ec2 get-console-output --region $REGION --instance-id "$IID" --latest --output text 2>/dev/null \
  | grep -E 'Topic|MIDDLEWARE_READY|error|Error' | tail -5

echo; echo "入口：$(terraform output -raw alb_url)/api"
