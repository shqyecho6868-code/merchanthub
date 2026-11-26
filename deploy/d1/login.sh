#!/bin/sh
# Log in for real through the gateway and print the Access-Token (last line)
#   TOKEN=$(./deploy/d1/login.sh m7_staff | tail -1)
cd "$(dirname "$0")/../.." || exit 1
USER=$1; PASS=${2:-password123}
[ -n "$USER" ] || { echo "Usage: $0 <username> [password]"; exit 1; }
RESP=$(curl -s -X POST http://localhost:10010/api/user/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
TOKEN=$(printf %s "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])' 2>/dev/null)
[ -n "$TOKEN" ] || { echo "Login failed: $RESP" >&2; exit 1; }
printf %s "$RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print("Logged in userId=%s role=%s tenantId=%s" % (d["userId"], d["role"], d["tenantId"]))' >&2
echo "$TOKEN"
