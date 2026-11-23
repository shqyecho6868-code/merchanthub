#!/bin/sh
# D1 🧑 实验：以某个账号登录，看他的待办（命令短，避免长 curl 粘贴时折行）
#   ./deploy/d1/todo.sh m7_admin
cd "$(dirname "$0")/../.." || exit 1
TOKEN=$(./deploy/d1/login.sh "$1" | tail -1) || exit 1
curl -s http://localhost:10010/api/approvals/tasks -H "Access-Token: $TOKEN" | python3 -m json.tool --no-ensure-ascii
