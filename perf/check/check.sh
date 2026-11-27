#!/bin/bash
# Post-load-test data consistency check. A load test only proves "fast"; this script proves "the data is still correct afterwards".
#   ./perf/check/check.sh [RUN_ID] [max wait seconds=60]
#   With RUN_ID it also checks: k6's "order placed" count = the merchant's orders in the DB (rate-limited / out-of-stock requests leave no order)
# Confirm-deduct / release after ordering is driven by async messages, so first wait for in-flight state to converge
# (reservations match order status), then run each check.
# Each check prints PASS / FAIL; any FAIL makes the exit code 1 (can be wired straight into a pipeline).
#
# Inventory accounting: reserve available-=q, reserved+=q (record status 10); confirm reserved-=q (20); release available+=q, reserved-=q (30)
# So for every SKU: initial available - current available = qty reserved(10) + qty confirmed(20); reserved = qty reserved(10)
cd "$(dirname "$0")/../.." || exit 1
# In the cloud there is no .env; MYSQL_CLI points at a client connected to RDS (perf/cloud/check.sh)
[ -f ./.env ] && { set -a; . ./.env; set +a; }
RUN_ID=$1
WAIT=${2:-60}
T="901,902"
# Initial stock matches perf/data/seed.sql; S2b overrides SKU 90101 with HOT_STOCK (seed.sh records it in seed-baseline.env)
HOT_STOCK=""; [ -f perf/results/seed-baseline.env ] && . perf/results/seed-baseline.env
if [ -n "$HOT_STOCK" ]; then
  INIT_SQL="IF(i.sku_id = 90101, $HOT_STOCK, IF(i.tenant_id = 901, 1000000, 100000))"
else
  INIT_SQL="IF(i.tenant_id = 901, 1000000, 100000)"
fi
MYSQL_CLI=${MYSQL_CLI:-docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot}
q() { $MYSQL_CLI -N -e "$1"; }
qt() { $MYSQL_CLI -t -e "$1"; }
FAIL=0
result() {   # $1=check name $2=bad row count $3=detail SQL (printed on failure)
  if [ "$2" = "0" ]; then
    printf '  \033[32mPASS\033[0m  %s\n' "$1"
  else
    printf '  \033[31mFAIL\033[0m  %s (%s bad rows)\n' "$1" "$2"; FAIL=1
    [ -n "$3" ] && qt "$3 LIMIT 10"
  fi
}

# Order status and reservation status must correspond: pending(10)<->RESERVED(10), paid and later(20/30/40)<->CONFIRMED(20), closed(50)<->RELEASED(30)
MISMATCH_SQL="SELECT o.order_no, o.status order_status, r.sku_id, r.status reservation_status
  FROM mh_order.orders o JOIN mh_product.inventory_reservation r ON r.order_no = o.order_no
  WHERE o.tenant_id IN ($T) AND NOT (
       (o.status = 10 AND r.status = 10) OR (o.status IN (20, 30, 40) AND r.status = 20) OR (o.status = 50 AND r.status = 30))"

printf 'Waiting for async deduct / release to converge (up to %ss)' "$WAIT"
for i in $(seq 1 "$WAIT"); do
  n=$(q "SELECT COUNT(*) FROM ($MISMATCH_SQL) x")
  [ "$n" = "0" ] && break
  printf '.'; sleep 1
done
echo " remaining mismatches: $n rows"

echo "== Totals =="
qt "SELECT o.tenant_id, COUNT(*) orders, SUM(o.status = 10) pending, SUM(o.status IN (20,30,40)) paid, SUM(o.status = 50) closed
    FROM mh_order.orders o WHERE o.tenant_id IN ($T) GROUP BY o.tenant_id"
qt "SELECT tenant_id, SUM(available) available, SUM(reserved) reserved FROM mh_product.inventory WHERE tenant_id IN ($T) GROUP BY tenant_id"

echo "== Inventory =="
SQL="SELECT tenant_id, sku_id, available, reserved FROM mh_product.inventory WHERE tenant_id IN ($T) AND (available < 0 OR reserved < 0)"
result "No negative stock (available / reserved both >= 0, no overselling)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT i.sku_id, $INIT_SQL - i.available AS deducted, COALESCE(SUM(r.quantity), 0) AS reserved_plus_confirmed
  FROM mh_product.inventory i LEFT JOIN mh_product.inventory_reservation r ON r.sku_id = i.sku_id AND r.status IN (10, 20)
  WHERE i.tenant_id IN ($T) GROUP BY i.sku_id, i.available, i.tenant_id HAVING deducted <> reserved_plus_confirmed"
result "Deduction reconciles: initial - available = reserved + confirmed (per SKU)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT i.sku_id, i.reserved, COALESCE(SUM(r.quantity), 0) AS reserving
  FROM mh_product.inventory i LEFT JOIN mh_product.inventory_reservation r ON r.sku_id = i.sku_id AND r.status = 10
  WHERE i.tenant_id IN ($T) GROUP BY i.sku_id, i.reserved HAVING i.reserved <> reserving"
result "reserved column = sum of quantities of RESERVED records (per SKU)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

echo "== Orders <-> inventory =="
SQL="SELECT o.order_no, oi.sku_id, oi.quantity, r.quantity reserved_qty
  FROM mh_order.orders o JOIN mh_order.order_item oi ON oi.order_no = o.order_no
  LEFT JOIN mh_product.inventory_reservation r ON r.order_no = o.order_no AND r.sku_id = oi.sku_id
  WHERE o.tenant_id IN ($T) AND (r.id IS NULL OR r.quantity <> oi.quantity)"
result "Every order line has a reservation with the same quantity (orders match deductions)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

result "Order status matches reservation status (pending<->reserved, paid<->confirmed, closed<->released)" \
  "$(q "SELECT COUNT(*) FROM ($MISMATCH_SQL) x")" "$MISMATCH_SQL"

# Dangling reservation = reserved but the order was never persisted (order transaction failed / timed out).
# Not an error: the dangling-reservation scan releases it; only the count is reported here
DANGLING=$(q "SELECT COUNT(*) FROM mh_product.inventory_reservation r LEFT JOIN mh_order.orders o ON o.order_no = r.order_no
  WHERE r.tenant_id IN ($T) AND o.order_no IS NULL AND r.status = 10")
echo "  INFO  dangling reservations (reserved but no order, waiting for the scan to release): $DANGLING"

echo "== Payment callback idempotency =="
SQL="SELECT order_no, COUNT(*) success_payments FROM mh_payment.payment WHERE tenant_id IN ($T) AND status = 1
  GROUP BY order_no HAVING COUNT(*) > 1"
result "At most one successful payment per order (duplicate callbacks take effect once)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT order_no, COUNT(*) times FROM mh_order.order_status_log WHERE tenant_id IN ($T) AND to_status = 20
  GROUP BY order_no HAVING COUNT(*) > 1"
result "At most one ->PAID transition in the status log (redelivery did not transition twice)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT p.order_no, p.pay_no, o.status FROM mh_payment.payment p JOIN mh_order.orders o ON o.order_no = p.order_no
  WHERE p.tenant_id IN ($T) AND p.status = 1 AND o.status NOT IN (20, 30, 40)
    AND NOT EXISTS (SELECT 1 FROM mh_order.pay_anomaly a WHERE a.pay_no = p.pay_no)"
result "Every successful payment's order is paid (payments after close go to pay_anomaly and are excluded)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT o.order_no, o.pay_amount, p.amount FROM mh_order.orders o
  JOIN mh_payment.payment p ON p.order_no = o.order_no AND p.status = 1
  WHERE o.tenant_id IN ($T) AND o.pay_amount <> p.amount"
result "Amount received = order amount payable" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

ANOMALY=$(q "SELECT COUNT(*) FROM mh_order.pay_anomaly WHERE tenant_id IN ($T)")
echo "  INFO  pay_anomaly records: $ANOMALY (paid after close / duplicate payment, left for manual reconciliation)"

# The three safety nets -- outbox scan, timeout close, reservation release. Each number is 0 when they work.
# Times are compared with UTC_TIMESTAMP: the app writes UTC (connectionTimeZone=UTC), while the CLI session may be in local time.
# GRACE_SEC matches merchanthub.order.jobs.timeout-grace: within it the timeout message is still expected to close the order
GRACE_SEC=${GRACE_SEC:-300}
echo "== Safety nets (outbox scan / timeout close / reservation release) =="
printf 'Waiting for the outbox to drain (up to %ss)' "$WAIT"
for i in $(seq 1 "$WAIT"); do
  n=$(q "SELECT COUNT(*) FROM mh_order.order_outbox WHERE status = 0")
  [ "$n" = "0" ] && break
  printf '.'; sleep 1
done
echo " pending: $n"
SQL="SELECT status, COUNT(*) n, MIN(created_at) oldest, MAX(retry_count) max_retries FROM mh_order.order_outbox WHERE status IN (0, 2) GROUP BY status"
result "Outbox undelivered = 0 (status 0 pending / 2 failed)" \
  "$(q "SELECT COUNT(*) FROM mh_order.order_outbox WHERE status IN (0, 2)")" "$SQL"

SQL="SELECT order_no, tenant_id, expire_at, created_at FROM mh_order.orders
  WHERE status = 10 AND deleted = 0 AND expire_at < UTC_TIMESTAMP(3) - INTERVAL $GRACE_SEC SECOND ORDER BY expire_at"
result "Orders past deadline + ${GRACE_SEC}s still pending payment = 0 (timeout close happened)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT o.order_no, o.tenant_id, o.updated_at closed_at, r.sku_id, r.quantity FROM mh_order.orders o
  JOIN mh_product.inventory_reservation r ON r.order_no = o.order_no WHERE o.status = 50 AND r.status = 10"
result "Closed orders whose reservation is still held = 0 (stock released)" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

if [ -n "$RUN_ID" ]; then
  echo "== k6 stats <-> orders in DB (${RUN_ID}) =="
  SUMMARY=perf/results/$RUN_ID/summary.json
  [ -f "$SUMMARY" ] || { echo "  $SUMMARY not found"; exit 1; }
  for t in 901 902; do
    K6=$(python3 -c "import json,sys; m=json.load(open('$SUMMARY'))['metrics'].get('biz_order{tenant:$t,result:ok}'); print(int(m['values']['count']) if m else -1)")
    [ "$K6" = "-1" ] && continue      # this scenario didn't load this merchant
    DB=$(q "SELECT COUNT(*) FROM mh_order.orders WHERE tenant_id = $t")
    if [ "$K6" = "$DB" ]; then
      printf '  \033[32mPASS\033[0m  merchant %s: k6 orders placed %s = orders in DB %s\n' "$t" "$K6" "$DB"
    else
      printf '  \033[31mFAIL\033[0m  merchant %s: k6 orders placed %s != orders in DB %s\n' "$t" "$K6" "$DB"; FAIL=1
    fi
  done
fi

echo
[ "$FAIL" = 0 ] && echo "All checks passed" || echo "Some checks failed, see the FAIL details above"
exit $FAIL
