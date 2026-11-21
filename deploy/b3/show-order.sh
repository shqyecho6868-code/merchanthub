#!/bin/sh
# 打印一个订单在各张表里的样子：订单、状态流水、outbox 事件、支付单、支付异常、预占记录、库存
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
O=$1
[ -n "$O" ] || { echo "用法：$0 订单号"; exit 1; }
q() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "$1"; }
echo "== 订单 =="
q "SELECT order_no, status, pay_amount, expire_at, paid_at, closed_at, close_reason FROM mh_order.orders WHERE order_no = $O"
echo "== 订单状态流水 =="
q "SELECT from_status, to_status, event, remark, created_at FROM mh_order.order_status_log WHERE order_no = $O ORDER BY id"
echo "== order 发出的事件（outbox）=="
q "SELECT topic, tag, status, deliver_at, sent_at FROM mh_order.order_outbox WHERE biz_key = '$O' ORDER BY id"
echo "== 支付单 =="
q "SELECT pay_no, status, channel_trade_no, paid_at FROM mh_payment.payment WHERE order_no = $O"
echo "== 支付异常（留给对账）=="
q "SELECT pay_no, type, order_status, handle_status, remark FROM mh_order.pay_anomaly WHERE order_no = $O"
echo "== 预占记录（10 预占 / 20 已确认 / 30 已释放）=="
q "SELECT sku_id, quantity, status, updated_at FROM mh_product.inventory_reservation WHERE order_no = $O"
echo "== 库存 SKU 1 =="
q "SELECT sku_id, available, reserved FROM mh_product.inventory WHERE sku_id = 1"
