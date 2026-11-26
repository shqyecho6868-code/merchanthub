#!/bin/bash
# Quick check whether the cloud environment is up.
#   ./infra/scripts/status.sh
# Expected: 6 services with running=1; both ALB target groups healthy; the middleware EC2 log ends with MIDDLEWARE_READY
set -uo pipefail
cd "$(dirname "$0")/../env"
REGION=us-east-1
CLUSTER=merchanthub

echo "== ECS services (desired / running / latest event)"
aws ecs describe-services --region $REGION --cluster $CLUSTER \
  --services gateway user-tenant product-inventory order payment realtime \
  --query 'services[].[serviceName, desiredCount, runningCount, events[0].message]' --output text

echo; echo "== ALB target health"
for TG in $(aws elbv2 describe-target-groups --region $REGION --names merchanthub-gateway merchanthub-realtime \
    --query 'TargetGroups[].TargetGroupArn' --output text); do
  echo "${TG##*:targetgroup/}"
  aws elbv2 describe-target-health --region $REGION --target-group-arn "$TG" \
    --query 'TargetHealthDescriptions[].[Target.Id, TargetHealth.State, TargetHealth.Reason]' --output text
done

echo; echo "== Middleware EC2 boot script (last 5 lines)"
IID=$(terraform output -raw middleware_instance_id)
aws ec2 get-console-output --region $REGION --instance-id "$IID" --latest --output text 2>/dev/null \
  | grep -E 'Topic|MIDDLEWARE_READY|error|Error' | tail -5

echo; echo "Entry point: $(terraform output -raw alb_url)/api"
