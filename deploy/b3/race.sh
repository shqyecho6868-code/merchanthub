#!/bin/sh
# B3-下 🧑 实验 ②：支付成功和超时关单同时到达。order 需用 ./deploy/b3/start-order.sh 20s 启动（支付时限 20 秒）
#   用法：./deploy/b3/race.sh [偏移秒数]
#   偏移 0：在订单到期那一刻回调（谁赢看运气）；-3：到期前 3 秒回调；3：到期后 3 秒回调
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
OFFSET=${1:-0}
TIMEOUT=20

RESP=$(./deploy/b2/place-order.sh)
O=$(echo "$RESP" | sed -n 's/.*"orderNo":"\([0-9]*\)".*/\1/p')
[ -n "$O" ] || { echo "下单失败：$RESP"; exit 1; }
echo "下单成功 orderNo=${O}，支付时限 ${TIMEOUT}s"

PAY=$(curl -s -X POST http://127.0.0.1:8108/payments -H 'Content-Type: application/json' -H 'X-User-Id: 1001' -d "{\"orderNo\":$O}")
PAY_NO=$(echo "$PAY" | sed -n 's/.*"payNo":\([0-9]*\).*/\1/p')
AMOUNT=$(echo "$PAY" | sed -n 's/.*"amount":\([0-9]*\).*/\1/p')
[ -n "$PAY_NO" ] || { echo "建支付单失败：$PAY"; exit 1; }
echo "支付单 payNo=${PAY_NO}，等 $((TIMEOUT + OFFSET)) 秒后回调（订单到期偏移 ${OFFSET}s）..."

sleep $((TIMEOUT + OFFSET))
# 请求体先放进变量、curl 单独一行：之前写成 echo "...$(curl -d "{\"...\"}")"，
# 双引号里嵌 $() 再嵌转义引号，macOS 的 bash 3.2 会把 JSON 拆成几段 → curl 发了 3 个残缺请求（实验 ② 第一次的"系统错误"）
BODY="{\"payNo\":$PAY_NO,\"channelTradeNo\":\"RACE-$PAY_NO\",\"amount\":$AMOUNT}"
printf '%s 回调：' "$(date '+%H:%M:%S')"
curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY"
echo
echo "等 10 秒让两条链路都跑完..."
sleep 10
./deploy/b3/show-order.sh "$O"
