#!/bin/sh
# Show the current state of two deliberately inconsistent orders (run before and after reconciliation to compare)
#   ./deploy/e3/show-recon.sh <orderNoA> <orderNoB>
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
q() { docker exec mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -t -e "$1" 2>/dev/null; }
echo "== Order A (payment succeeded but order still pending payment) =="
q "SELECT pay_no, order_no, status AS payment_status, recon_status, recon_retry FROM mh_payment.payment WHERE order_no = $1;"
q "SELECT order_no, status AS order_status, paid_at FROM mh_order.orders WHERE order_no = $1;"
echo "== Order B (order closed but reservation not released) =="
q "SELECT order_no, status AS order_status, close_reason FROM mh_order.orders WHERE order_no = $2;"
q "SELECT order_no, sku_id, quantity, status AS reservation_status FROM mh_product.inventory_reservation WHERE order_no = $2;"
q "SELECT sku_id, available, reserved FROM mh_product.inventory WHERE tenant_id = 7 AND sku_id = 1;"
