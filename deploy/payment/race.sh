#!/bin/sh
# Experiment 2: payment success and timeout close arrive at the same time. Start order with ./deploy/payment/start-order.sh 20s (20-second payment deadline)
#   Usage: ./deploy/payment/race.sh [offsetSeconds]
#   Offset 0: callback right at expiry (the winner is luck); -3: 3 seconds before expiry; 3: 3 seconds after expiry
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
OFFSET=${1:-0}
TIMEOUT=20

RESP=$(./deploy/order-events/place-order.sh)
O=$(echo "$RESP" | sed -n 's/.*"orderNo":"\([0-9]*\)".*/\1/p')
[ -n "$O" ] || { echo "Order failed: $RESP"; exit 1; }
echo "Order placed orderNo=${O}, payment deadline ${TIMEOUT}s"

PAY=$(curl -s -X POST http://127.0.0.1:8108/payments -H 'Content-Type: application/json' -H 'X-User-Id: 1001' -d "{\"orderNo\":$O}")
PAY_NO=$(echo "$PAY" | sed -n 's/.*"payNo":\([0-9]*\).*/\1/p')
AMOUNT=$(echo "$PAY" | sed -n 's/.*"amount":\([0-9]*\).*/\1/p')
[ -n "$PAY_NO" ] || { echo "Failed to create payment: $PAY"; exit 1; }
echo "Payment payNo=${PAY_NO}, calling back in $((TIMEOUT + OFFSET)) seconds (offset ${OFFSET}s from order expiry)..."

sleep $((TIMEOUT + OFFSET))
# Put the request body in a variable and run curl on its own line: an earlier echo "...$(curl -d "{\"...\"}")"
# (double quotes wrapping $() wrapping escaped quotes) made macOS bash 3.2 split the JSON, so curl sent 3 broken requests
BODY="{\"payNo\":$PAY_NO,\"channelTradeNo\":\"RACE-$PAY_NO\",\"amount\":$AMOUNT}"
printf '%s callback: ' "$(date '+%H:%M:%S')"
curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY"
echo
echo "Waiting 10 seconds for both paths to finish..."
sleep 10
./deploy/payment/show-order.sh "$O"
