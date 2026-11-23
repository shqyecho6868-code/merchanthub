#!/bin/sh
# C1 🧑 实验：签发一个带身份的 Access-Token，并写进 Redis（网关会核对 Redis 里是不是这个 token）。
# 登录接口还是旧 IM 代码（D1 改造），实验先用这个脚本代替"登录"。
#   ./deploy/c1/token.sh 用户ID 角色 [商家ID]
#   ./deploy/c1/token.sh 2001 MERCHANT_ADMIN 7     → 商家 7 的管理员
#   ./deploy/c1/token.sh 50001 BUYER               → 买家（不带商家 ID）
# 输出：token 本身（最后一行），可以 TOKEN=$(./deploy/c1/token.sh ... | tail -1)
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
USER_ID=$1; ROLE=$2; TENANT=$3
[ -n "$USER_ID" ] && [ -n "$ROLE" ] || { echo "用法：$0 用户ID 角色 [商家ID]"; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# 签名密钥 = JwtUtil.getSignInKey() 的字节（十六进制）。不能用 openssl / base64 命令自己解 CommonConstant.TOKEN_SECRET_KEY：
# 那个字符串 62 个字符、没补 "="，jjwt 的解码器比较宽松，会多解出一个字节（6a），命令行工具会把它丢掉 → 签名对不上。
# 遗留：密钥应移到配置（见 progress），移走后这里同步改
HEXKEY=b216a7c9a9e072875e6da7247a77489df8a78ad78285ab6c85a9f26a781ca1d79b69c91e9dd2277e29e2b5e0a16a

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
