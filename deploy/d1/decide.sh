#!/bin/sh
# D1 🧑 实验：以某个账号登录，通过 / 驳回一条待办
#   ./deploy/d1/decide.sh m7_admin <taskId> approve [意见]
#   ./deploy/d1/decide.sh platform_admin <taskId> reject 主图不清晰
cd "$(dirname "$0")/../.." || exit 1
USER=$1; TASK=$2; ACTION=${3:-approve}; COMMENT=${4:-同意}
[ -n "$USER" ] && [ -n "$TASK" ] || { echo "用法：$0 登录名 taskId [approve|reject] [意见]"; exit 1; }
TOKEN=$(./deploy/d1/login.sh "$USER" | tail -1) || exit 1
curl -s -X POST "http://localhost:10010/api/approvals/tasks/$TASK/$ACTION" -H "Access-Token: $TOKEN" \
  -H "Content-Type: application/json" -d "{\"comment\":\"$COMMENT\"}"
echo
