#!/bin/sh
# E3 🧑：查看两条造出来的不一致数据当前的状态（对账前后各跑一次对比）
#   ./deploy/e3/show-recon.sh <订单号A> <订单号B>
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
q() { docker exec mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -t -e "$1" 2>/dev/null; }
echo "== 订单 A（支付成功但订单待支付）=="
q "SELECT pay_no, order_no, status AS 支付单状态, recon_status, recon_retry FROM mh_payment.payment WHERE order_no = $1;"
q "SELECT order_no, status AS 订单状态, paid_at FROM mh_order.orders WHERE order_no = $1;"
echo "== 订单 B（订单已关闭但预占没释放）=="
q "SELECT order_no, status AS 订单状态, close_reason FROM mh_order.orders WHERE order_no = $2;"
q "SELECT order_no, sku_id, quantity, status AS 预占状态 FROM mh_product.inventory_reservation WHERE order_no = $2;"
q "SELECT sku_id, available, reserved FROM mh_product.inventory WHERE tenant_id = 7 AND sku_id = 1;"
