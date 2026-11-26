#!/bin/sh
# Log in as an account and approve / reject one pending task
#   ./deploy/approval/decide.sh m7_admin <taskId> approve [comment]
#   ./deploy/approval/decide.sh platform_admin <taskId> reject "Main image is blurry"
cd "$(dirname "$0")/../.." || exit 1
USER=$1; TASK=$2; ACTION=${3:-approve}; COMMENT=${4:-Approved}
[ -n "$USER" ] && [ -n "$TASK" ] || { echo "Usage: $0 <username> <taskId> [approve|reject] [comment]"; exit 1; }
TOKEN=$(./deploy/approval/login.sh "$USER" | tail -1) || exit 1
curl -s -X POST "http://localhost:10010/api/approvals/tasks/$TASK/$ACTION" -H "Access-Token: $TOKEN" \
  -H "Content-Type: application/json" -d "{\"comment\":\"$COMMENT\"}"
echo
