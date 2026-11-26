#!/bin/sh
# Experiment: fire the callback of the same payment several times and see what blocks the duplicates.
#   Usage: ./deploy/b3/dup-callback.sh <orderNo>
# Steps: create a payment -> 5 concurrent callbacks (same channelTradeNo) -> wait 1 second, then 2 more sequential ones -> print payment, order, history, dedup records
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
ORDER_NO=$1
[ -n "$ORDER_NO" ] || { echo "Usage: $0 <orderNo>"; exit 1; }

RESP=$(curl -s -X POST http://127.0.0.1:8108/payments -H 'Content-Type: application/json' -H 'X-User-Id: 1001' \
  -d "{\"orderNo\":$ORDER_NO}")
echo "Create payment: $RESP"
PAY_NO=$(echo "$RESP" | sed -n 's/.*"payNo":\([0-9]*\).*/\1/p')
AMOUNT=$(echo "$RESP" | sed -n 's/.*"amount":\([0-9]*\).*/\1/p')
[ -n "$PAY_NO" ] || { echo "Failed to create payment"; exit 1; }
BODY="{\"payNo\":$PAY_NO,\"channelTradeNo\":\"MOCK-$PAY_NO\",\"amount\":$AMOUNT}"

echo "\n== 5 concurrent callbacks =="
for i in 1 2 3 4 5; do
  curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY" \
    | sed "s/^/  #$i (concurrent): /" &
done
wait
echo
sleep 1
echo "== 2 more, sequential =="
for i in 6 7; do
  curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY" \
    | sed "s/^/  #$i (sequential): /"
  echo
done

q() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "$1"; }
# Wait for order to consume: if PAYMENT_EVENT is auto-created on first send, the order consumer only discovers it after a route refresh (30 seconds by default)
printf "\nWaiting for order to consume"
for i in $(seq 1 60); do
  S=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N -e "SELECT status FROM mh_order.orders WHERE order_no = $ORDER_NO")
  [ "$S" = "20" ] && break
  printf "."; sleep 1
done
echo " ${i}s"
echo "\n== Payment =="
q "SELECT pay_no, status, channel_trade_no, paid_at, success_attempt FROM mh_payment.payment WHERE pay_no = $PAY_NO"
echo "== Order =="
q "SELECT order_no, status, paid_at FROM mh_order.orders WHERE order_no = $ORDER_NO"
echo "== Order status history =="
q "SELECT from_status, to_status, event, operator_type, remark, created_at FROM mh_order.order_status_log WHERE order_no = $ORDER_NO ORDER BY id"
echo "== order consumer dedup records (event_id = pay_no) =="
q "SELECT consumer_group, event_id, created_at FROM mh_order.consumed_event WHERE event_id = $PAY_NO"
