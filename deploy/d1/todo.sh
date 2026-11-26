#!/bin/sh
# Log in as an account and list its pending tasks (a short command, so long curl lines don't wrap when pasted)
#   ./deploy/d1/todo.sh m7_admin
cd "$(dirname "$0")/../.." || exit 1
TOKEN=$(./deploy/d1/login.sh "$1" | tail -1) || exit 1
curl -s http://localhost:10010/api/approvals/tasks -H "Access-Token: $TOKEN" | python3 -m json.tool --no-ensure-ascii
