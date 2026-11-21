#!/bin/sh
# B3 🧑 实验：同一笔支付的回调连发多次，看谁挡住了重复。
#   用法：./deploy/b3/dup-callback.sh 订单号
# 步骤：建一张支付单 → 同时并发 5 次回调（同一 channelTradeNo）→ 等 1 秒再先后补发 2 次 → 打印支付单、订单、流水、去重记录
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
ORDER_NO=$1
[ -n "$ORDER_NO" ] || { echo "用法：$0 订单号"; exit 1; }

RESP=$(curl -s -X POST http://127.0.0.1:8108/payments -H 'Content-Type: application/json' -H 'X-User-Id: 1001' \
  -d "{\"orderNo\":$ORDER_NO}")
echo "建支付单：$RESP"
PAY_NO=$(echo "$RESP" | sed -n 's/.*"payNo":\([0-9]*\).*/\1/p')
AMOUNT=$(echo "$RESP" | sed -n 's/.*"amount":\([0-9]*\).*/\1/p')
[ -n "$PAY_NO" ] || { echo "建支付单失败"; exit 1; }
BODY="{\"payNo\":$PAY_NO,\"channelTradeNo\":\"MOCK-$PAY_NO\",\"amount\":$AMOUNT}"

echo "\n== 并发 5 次回调 =="
for i in 1 2 3 4 5; do
  curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY" \
    | sed "s/^/  第 $i 次（并发）：/" &
done
wait
echo
sleep 1
echo "== 先后再补发 2 次 =="
for i in 6 7; do
  curl -s -X POST http://127.0.0.1:8108/payments/callback/mock -H 'Content-Type: application/json' -d "$BODY" \
    | sed "s/^/  第 $i 次（先后）：/"
  echo
done

q() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "$1"; }
# 等 order 消费：PAYMENT_EVENT 第一次发送时才自动创建，order 消费者要等路由刷新（默认 30 秒）才能发现它（pitfalls P20）
printf "\n等待 order 消费"
for i in $(seq 1 60); do
  S=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N -e "SELECT status FROM mh_order.orders WHERE order_no = $ORDER_NO")
  [ "$S" = "20" ] && break
  printf "."; sleep 1
done
echo " ${i}s"
echo "\n== 支付单 =="
q "SELECT pay_no, status, channel_trade_no, paid_at, success_attempt FROM mh_payment.payment WHERE pay_no = $PAY_NO"
echo "== 订单 =="
q "SELECT order_no, status, paid_at FROM mh_order.orders WHERE order_no = $ORDER_NO"
echo "== 订单状态流水 =="
q "SELECT from_status, to_status, event, operator_type, remark, created_at FROM mh_order.order_status_log WHERE order_no = $ORDER_NO ORDER BY id"
echo "== order 消费去重记录（event_id = pay_no）=="
q "SELECT consumer_group, event_id, created_at FROM mh_order.consumed_event WHERE event_id = $PAY_NO"
