#!/bin/bash
# F1：压测后的数据一致性检查。压测只证明"快"，这个脚本证明"压完数据还是对的"。
#   ./perf/check/check.sh [RUN_ID] [最长等待秒数=60]
#   带 RUN_ID 时额外核对：k6 统计的"下单成功"次数 = 库里该商家的订单数（被限流 / 库存不足的请求没有留下订单）
# 下单后的确认扣减 / 释放是异步消息驱动的，所以先等"在途"状态收敛（预占数与订单状态对上），再逐项判定。
# 每一项输出 PASS / FAIL；任何一项 FAIL 退出码为 1（G 阶段可直接接进流水线）。
#
# 库存记账规则（B1 / B3-下）：预占 available-=q, reserved+=q（记录 10）；确认 reserved-=q（20）；释放 available+=q, reserved-=q（30）
# 所以对每个 SKU 恒有：初始可售 - 当前可售 = 预占中(10) + 已确认(20) 的数量；reserved = 预占中(10) 的数量
cd "$(dirname "$0")/../.." || exit 1
# G-下：云上执行时没有 .env，由 MYSQL_CLI 指定连 RDS 的客户端（perf/cloud/check.sh）
[ -f ./.env ] && { set -a; . ./.env; set +a; }
RUN_ID=$1
WAIT=${2:-60}
T="901,902"
# 初始库存与 perf/data/seed.sql 一致；S2b 用 HOT_STOCK 改过 SKU 90101（seed.sh 记在 seed-baseline.env）
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
result() {   # $1=检查名 $2=异常行数 $3=明细 SQL（异常时打印）
  if [ "$2" = "0" ]; then
    printf '  \033[32mPASS\033[0m  %s\n' "$1"
  else
    printf '  \033[31mFAIL\033[0m  %s（%s 行异常）\n' "$1" "$2"; FAIL=1
    [ -n "$3" ] && qt "$3 LIMIT 10"
  fi
}

# 订单状态与预占记录状态应一一对应：待支付(10)↔RESERVED(10)、已支付及之后(20/30/40)↔CONFIRMED(20)、已关闭(50)↔RELEASED(30)
MISMATCH_SQL="SELECT o.order_no, o.status order_status, r.sku_id, r.status reservation_status
  FROM mh_order.orders o JOIN mh_product.inventory_reservation r ON r.order_no = o.order_no
  WHERE o.tenant_id IN ($T) AND NOT (
       (o.status = 10 AND r.status = 10) OR (o.status IN (20, 30, 40) AND r.status = 20) OR (o.status = 50 AND r.status = 30))"

printf '等待异步扣减 / 释放收敛（最多 %ss）' "$WAIT"
for i in $(seq 1 "$WAIT"); do
  n=$(q "SELECT COUNT(*) FROM ($MISMATCH_SQL) x")
  [ "$n" = "0" ] && break
  printf '.'; sleep 1
done
echo " 剩余不一致 $n 行"

echo "== 总量 =="
qt "SELECT o.tenant_id, COUNT(*) orders, SUM(o.status = 10) pending, SUM(o.status IN (20,30,40)) paid, SUM(o.status = 50) closed
    FROM mh_order.orders o WHERE o.tenant_id IN ($T) GROUP BY o.tenant_id"
qt "SELECT tenant_id, SUM(available) available, SUM(reserved) reserved FROM mh_product.inventory WHERE tenant_id IN ($T) GROUP BY tenant_id"

echo "== 库存 =="
SQL="SELECT tenant_id, sku_id, available, reserved FROM mh_product.inventory WHERE tenant_id IN ($T) AND (available < 0 OR reserved < 0)"
result "库存无负数（available / reserved 都 >= 0，没有超卖）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT i.sku_id, $INIT_SQL - i.available AS deducted, COALESCE(SUM(r.quantity), 0) AS reserved_plus_confirmed
  FROM mh_product.inventory i LEFT JOIN mh_product.inventory_reservation r ON r.sku_id = i.sku_id AND r.status IN (10, 20)
  WHERE i.tenant_id IN ($T) GROUP BY i.sku_id, i.available, i.tenant_id HAVING deducted <> reserved_plus_confirmed"
result "扣减对账：初始 - 可售 = 预占中 + 已确认（每个 SKU）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT i.sku_id, i.reserved, COALESCE(SUM(r.quantity), 0) AS reserving
  FROM mh_product.inventory i LEFT JOIN mh_product.inventory_reservation r ON r.sku_id = i.sku_id AND r.status = 10
  WHERE i.tenant_id IN ($T) GROUP BY i.sku_id, i.reserved HAVING i.reserved <> reserving"
result "reserved 字段 = 预占中记录数量之和（每个 SKU）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

echo "== 订单 ↔ 库存 =="
SQL="SELECT o.order_no, oi.sku_id, oi.quantity, r.quantity reserved_qty
  FROM mh_order.orders o JOIN mh_order.order_item oi ON oi.order_no = o.order_no
  LEFT JOIN mh_product.inventory_reservation r ON r.order_no = o.order_no AND r.sku_id = oi.sku_id
  WHERE o.tenant_id IN ($T) AND (r.id IS NULL OR r.quantity <> oi.quantity)"
result "每个订单明细都有数量相同的预占记录（订单数和扣减数对得上）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

result "订单状态与预占状态一致（待支付↔预占中、已支付↔已确认、已关闭↔已释放）" \
  "$(q "SELECT COUNT(*) FROM ($MISMATCH_SQL) x")" "$MISMATCH_SQL"

# 悬空预占 = 预占成功但订单没落库（下单事务失败 / 超时）。不算错：由悬空扫描任务释放（D35），这里只报数量
DANGLING=$(q "SELECT COUNT(*) FROM mh_product.inventory_reservation r LEFT JOIN mh_order.orders o ON o.order_no = r.order_no
  WHERE r.tenant_id IN ($T) AND o.order_no IS NULL AND r.status = 10")
echo "  INFO  悬空预占（有预占无订单，待悬空扫描释放）：$DANGLING 条"

echo "== 支付回调幂等 =="
SQL="SELECT order_no, COUNT(*) success_payments FROM mh_payment.payment WHERE tenant_id IN ($T) AND status = 1
  GROUP BY order_no HAVING COUNT(*) > 1"
result "一个订单最多一笔成功支付（重复回调只生效一次）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT order_no, COUNT(*) times FROM mh_order.order_status_log WHERE tenant_id IN ($T) AND to_status = 20
  GROUP BY order_no HAVING COUNT(*) > 1"
result "订单流水里 →已支付 最多一次（重复消费没有重复流转）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT p.order_no, p.pay_no, o.status FROM mh_payment.payment p JOIN mh_order.orders o ON o.order_no = p.order_no
  WHERE p.tenant_id IN ($T) AND p.status = 1 AND o.status NOT IN (20, 30, 40)
    AND NOT EXISTS (SELECT 1 FROM mh_order.pay_anomaly a WHERE a.pay_no = p.pay_no)"
result "成功支付单对应的订单都已支付（关单后到账的记入 pay_anomaly，不算）" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

SQL="SELECT o.order_no, o.pay_amount, p.amount FROM mh_order.orders o
  JOIN mh_payment.payment p ON p.order_no = o.order_no AND p.status = 1
  WHERE o.tenant_id IN ($T) AND o.pay_amount <> p.amount"
result "实收金额 = 订单应付金额" "$(q "SELECT COUNT(*) FROM ($SQL) x")" "$SQL"

ANOMALY=$(q "SELECT COUNT(*) FROM mh_order.pay_anomaly WHERE tenant_id IN ($T)")
echo "  INFO  支付异常记录 pay_anomaly：$ANOMALY 条（关单后到账 / 重复支付，待对账人工处理）"

if [ -n "$RUN_ID" ]; then
  echo "== k6 统计 ↔ 库里订单（${RUN_ID}）=="
  SUMMARY=perf/results/$RUN_ID/summary.json
  [ -f "$SUMMARY" ] || { echo "  找不到 $SUMMARY"; exit 1; }
  for t in 901 902; do
    K6=$(python3 -c "import json,sys; m=json.load(open('$SUMMARY'))['metrics'].get('biz_order{tenant:$t,result:ok}'); print(int(m['values']['count']) if m else -1)")
    [ "$K6" = "-1" ] && continue      # 这个场景没压该商家
    DB=$(q "SELECT COUNT(*) FROM mh_order.orders WHERE tenant_id = $t")
    if [ "$K6" = "$DB" ]; then
      printf '  \033[32mPASS\033[0m  商家 %s：k6 下单成功 %s 次 = 库里订单 %s 条\n' "$t" "$K6" "$DB"
    else
      printf '  \033[31mFAIL\033[0m  商家 %s：k6 下单成功 %s 次 ≠ 库里订单 %s 条\n' "$t" "$K6" "$DB"; FAIL=1
    fi
  done
fi

echo
[ "$FAIL" = 0 ] && echo "全部检查通过" || echo "有检查未通过，见上方 FAIL 明细"
exit $FAIL
