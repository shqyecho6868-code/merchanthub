#!/bin/sh
# D1：经网关真实登录（不再用 C1 的 token.sh 手工签），输出 Access-Token（最后一行）
#   TOKEN=$(./deploy/d1/login.sh m7_staff | tail -1)
cd "$(dirname "$0")/../.." || exit 1
USER=$1; PASS=${2:-password123}
[ -n "$USER" ] || { echo "用法：$0 登录名 [密码]"; exit 1; }
RESP=$(curl -s -X POST http://localhost:10010/api/user/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(printf %s "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])' 2>/dev/null)
[ -n "$TOKEN" ] || { echo "登录失败：$RESP" >&2; exit 1; }
printf %s "$RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print("已登录 userId=%s role=%s tenantId=%s" % (d["userId"], d["role"], d["tenantId"]))' >&2
echo "$TOKEN"
