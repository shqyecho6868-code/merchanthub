#!/bin/sh
# Issue an Access-Token carrying an identity and store it in Redis (the gateway checks that Redis holds this exact token).
#   ./deploy/c1/token.sh <userId> <role> [tenantId]
#   ./deploy/c1/token.sh 2001 MERCHANT_ADMIN 7     -> admin of merchant 7
#   ./deploy/c1/token.sh 50001 BUYER               -> buyer (no tenant ID)
# Output: the token itself (last line), e.g. TOKEN=$(./deploy/c1/token.sh ... | tail -1)
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
USER_ID=$1; ROLE=$2; TENANT=$3
[ -n "$USER_ID" ] && [ -n "$ROLE" ] || { echo "Usage: $0 <userId> <role> [tenantId]"; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# The key is JWT_SECRET from .env (padded standard Base64); base64-decoding it on the command line yields exactly the same bytes as Java.
# For normal experiments use the real login API (deploy/d1/login.sh); this script is for "I need some identity that has no account in the DB"
HEXKEY=$(printf %s "$JWT_SECRET" | openssl base64 -d -A | xxd -p | tr -d '\n')

NOW=$(date +%s); EXP=$((NOW + 3600))
if [ -n "$TENANT" ]; then
  PAYLOAD="{\"sub\":\"$USER_ID\",\"role\":\"$ROLE\",\"tenantId\":\"$TENANT\",\"iat\":$NOW,\"exp\":$EXP}"
else
  PAYLOAD="{\"sub\":\"$USER_ID\",\"role\":\"$ROLE\",\"iat\":$NOW,\"exp\":$EXP}"
fi
H=$(printf %s '{"alg":"HS256"}' | b64url)
P=$(printf %s "$PAYLOAD" | b64url)
S=$(printf %s "$H.$P" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"$HEXKEY" -binary | b64url)
TOKEN="$H.$P.$S"

# The gateway uses Redis db 2 (GateWay application-dev.yml)
docker exec mh-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning -n 2 SET "access:token:$USER_ID" "$TOKEN" EX 3600 >/dev/null
echo "Issued: userId=$USER_ID role=$ROLE tenantId=${TENANT:-none}, valid for 1 hour" >&2
echo "$TOKEN"
