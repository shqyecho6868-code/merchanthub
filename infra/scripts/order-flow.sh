#!/bin/bash
# Run one full transaction through the ALB: buyer login -> place order -> create payment -> mock channel callback -> query order (should be paid).
# Same path as perf/k6/smoke.js, but step by step with curl, so the order number can be searched in CloudWatch logs.
#   ./infra/scripts/order-flow.sh
set -euo pipefail
cd "$(dirname "$0")/../env"
BASE="$(terraform output -raw alb_url)/api"
TENANT=902      # the small merchant in perf/data/seed.sql
SKU=90201
J='Content-Type: application/json'

# Order / payment numbers are 19-digit numbers; parse with Python (no precision loss like in JS)
field() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"][sys.argv[1]] if d.get("code")==200 else "ERR:"+json.dumps(d,ensure_ascii=False))' "$1"; }
step() { echo; echo "== $1"; }

step "1. Buyer login (perf_buyer_0001)"
LOGIN=$(curl -sS -X POST "$BASE/user/login" -H "$J" -d '{"username":"perf_buyer_0001","password":"password123"}')
TOKEN=$(echo "$LOGIN" | field accessToken)
[[ "$TOKEN" == ERR:* ]] && { echo "$TOKEN"; exit 1; }
echo "Got token: ${TOKEN:0:16}..."

step "2. Place order: merchant $TENANT, SKU $SKU x 1"
ORDER=$(curl -sS -X POST "$BASE/orders" -H "$J" -H "Access-Token: $TOKEN" -d "{\"tenantId\":$TENANT,\"items\":[{\"skuId\":$SKU,\"quantity\":1}]}")
echo "$ORDER"
ORDER_NO=$(echo "$ORDER" | field orderNo)
[[ "$ORDER_NO" == ERR:* ]] && exit 1

step "3. Create payment"
PAY=$(curl -sS -X POST "$BASE/payments" -H "$J" -H "Access-Token: $TOKEN" -d "{\"orderNo\":$ORDER_NO}")
echo "$PAY"
PAY_NO=$(echo "$PAY" | field payNo)
AMOUNT=$(echo "$PAY" | field amount)
[[ "$PAY_NO" == ERR:* ]] && exit 1

step "4. Mock payment channel callback (payNo=${PAY_NO}, amount $AMOUNT cents)"
curl -sS -X POST "$BASE/payments/callback/mock" -H "$J" \
  -d "{\"payNo\":$PAY_NO,\"channelTradeNo\":\"AWS-$PAY_NO\",\"amount\":$AMOUNT}"
echo

step "5. Query order (the payment event reaches order asynchronously via RocketMQ; wait 3 seconds)"
sleep 3
curl -sS "$BASE/orders/mine/$ORDER_NO" -H "Access-Token: $TOKEN"
echo; echo
echo "Order number: $ORDER_NO -- search for it in CloudWatch Logs Insights"
