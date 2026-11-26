#!/bin/sh
# Print what an order looks like across tables: order, status history, outbox events, payments, payment anomalies, reservations, inventory
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
O=$1
[ -n "$O" ] || { echo "Usage: $0 <orderNo>"; exit 1; }
q() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "$1"; }
echo "== Order =="
q "SELECT order_no, status, pay_amount, expire_at, paid_at, closed_at, close_reason FROM mh_order.orders WHERE order_no = $O"
echo "== Order status history =="
q "SELECT from_status, to_status, event, remark, created_at FROM mh_order.order_status_log WHERE order_no = $O ORDER BY id"
echo "== Events sent by order (outbox) =="
q "SELECT topic, tag, status, deliver_at, sent_at FROM mh_order.order_outbox WHERE biz_key = '$O' ORDER BY id"
echo "== Payments =="
q "SELECT pay_no, status, channel_trade_no, paid_at FROM mh_payment.payment WHERE order_no = $O"
echo "== Payment anomalies (left for reconciliation) =="
q "SELECT pay_no, type, order_status, handle_status, remark FROM mh_order.pay_anomaly WHERE order_no = $O"
echo "== Reservations (10 reserved / 20 confirmed / 30 released) =="
q "SELECT sku_id, quantity, status, updated_at FROM mh_product.inventory_reservation WHERE order_no = $O"
echo "== Inventory of SKU 1 =="
q "SELECT sku_id, available, reserved FROM mh_product.inventory WHERE sku_id = 1"
