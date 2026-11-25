#!/bin/bash
# G-上：用 ALB 地址走一遍完整交易：买家登录 → 下单 → 建支付单 → 模拟渠道回调 → 查单（应为已支付）。
# 和 perf/k6/smoke.js 是同一条链路，这里用 curl 一步一步打印出来，方便拿订单号去 CloudWatch 搜日志。
#   ./infra/scripts/order-flow.sh
set -euo pipefail
cd "$(dirname "$0")/../env"
BASE="$(terraform output -raw alb_url)/api"
TENANT=902      # perf/data/seed.sql 里的小商家
SKU=90201
J='Content-Type: application/json'

# 订单号 / 支付单号是 19 位数字，用 Python 解析（不会像 JS 那样丢精度）
field() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"][sys.argv[1]] if d.get("code")==200 else "ERR:"+json.dumps(d,ensure_ascii=False))' "$1"; }
step() { echo; echo "== $1"; }

step "1. 买家登录（perf_buyer_0001）"
LOGIN=$(curl -sS -X POST "$BASE/user/login" -H "$J" -d '{"username":"perf_buyer_0001","password":"password123"}')
TOKEN=$(echo "$LOGIN" | field accessToken)
[[ "$TOKEN" == ERR:* ]] && { echo "$TOKEN"; exit 1; }
echo "拿到 token：${TOKEN:0:16}..."

step "2. 下单：商家 $TENANT 的 SKU $SKU × 1"
ORDER=$(curl -sS -X POST "$BASE/orders" -H "$J" -H "Access-Token: $TOKEN" -d "{\"tenantId\":$TENANT,\"items\":[{\"skuId\":$SKU,\"quantity\":1}]}")
echo "$ORDER"
ORDER_NO=$(echo "$ORDER" | field orderNo)
[[ "$ORDER_NO" == ERR:* ]] && exit 1

step "3. 建支付单"
PAY=$(curl -sS -X POST "$BASE/payments" -H "$J" -H "Access-Token: $TOKEN" -d "{\"orderNo\":$ORDER_NO}")
echo "$PAY"
PAY_NO=$(echo "$PAY" | field payNo)
AMOUNT=$(echo "$PAY" | field amount)
[[ "$PAY_NO" == ERR:* ]] && exit 1

step "4. 模拟支付渠道回调（payNo=$PAY_NO，金额 $AMOUNT 分）"
curl -sS -X POST "$BASE/payments/callback/mock" -H "$J" \
  -d "{\"payNo\":$PAY_NO,\"channelTradeNo\":\"AWS-$PAY_NO\",\"amount\":$AMOUNT}"
echo

step "5. 查单（支付事件经 RocketMQ 异步到 order，稍等 3 秒）"
sleep 3
curl -sS "$BASE/orders/mine/$ORDER_NO" -H "Access-Token: $TOKEN"
echo; echo
echo "订单号：$ORDER_NO —— 去 CloudWatch Logs Insights 搜它（查询语句见 docs/runbook.md 第 5 节）"
