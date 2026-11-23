#!/bin/sh
# C1 🧑 实验：签发一个带身份的 Access-Token，并写进 Redis（网关会核对 Redis 里是不是这个 token）。
#   ./deploy/c1/token.sh 用户ID 角色 [商家ID]
#   ./deploy/c1/token.sh 2001 MERCHANT_ADMIN 7     → 商家 7 的管理员
#   ./deploy/c1/token.sh 50001 BUYER               → 买家（不带商家 ID）
# 输出：token 本身（最后一行），可以 TOKEN=$(./deploy/c1/token.sh ... | tail -1)
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
USER_ID=$1; ROLE=$2; TENANT=$3
[ -n "$USER_ID" ] && [ -n "$ROLE" ] || { echo "用法：$0 用户ID 角色 [商家ID]"; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# D1 起密钥来自 .env 的 JWT_SECRET（补齐的标准 Base64），命令行 base64 解出来的字节和 Java 完全一致（P26 已修）。
# 登录接口已是新代码，正常实验用 deploy/d1/login.sh；这个脚本留给"要造某个身份但库里没有这个账号"的场景
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

# 网关用 Redis db 2（GateWay application-dev.yml）
docker exec mh-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning -n 2 SET "access:token:$USER_ID" "$TOKEN" EX 3600 >/dev/null
echo "已签发：userId=$USER_ID role=$ROLE tenantId=${TENANT:-无}，1 小时有效" >&2
echo "$TOKEN"
