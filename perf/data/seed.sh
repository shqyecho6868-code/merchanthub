#!/bin/bash
# F1：每轮压测前执行。① 清掉压测商家（901 / 902）上一轮产生的所有业务数据 ② 灌 / 重置 seed.sql 里的基础数据。
# 清理是动态的：除 mh_user 外所有 mh_* 库里带 tenant_id 列的表，删 tenant_id IN (901, 902) 的行；
# 只保留商品、SKU、库存这三张基础表（由 seed.sql 重置）。以后加了新表也会被自动清理。
#   ./perf/data/seed.sh
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
TENANTS="901,902"
sql() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N "$@"; }

echo "清理压测商家 $TENANTS 的历史数据："
TABLES=$(sql -e "SELECT CONCAT(table_schema, '.', table_name) FROM information_schema.columns
  WHERE column_name = 'tenant_id' AND table_schema LIKE 'mh\\_%' AND table_schema <> 'mh_user'
    AND table_name NOT IN ('product', 'sku', 'inventory')")
for t in $TABLES; do
  n=$(sql -e "DELETE FROM $t WHERE tenant_id IN ($TENANTS); SELECT ROW_COUNT();")
  [ "$n" != "0" ] && echo "  ${t}：删除 ${n} 行"
done

echo "灌基础数据（商家、账号、商品、SKU、库存重置）"
sql < perf/data/seed.sql || exit 1
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "SELECT tenant_id, COUNT(*) skus, SUM(available) available, SUM(reserved) reserved
  FROM mh_product.inventory WHERE tenant_id IN ($TENANTS) GROUP BY tenant_id;
  SELECT COUNT(*) buyers FROM mh_user.account WHERE username LIKE 'perf_buyer_%';"
echo "完成。ES 里的旧订单文档不影响压测（搜索场景需要时手动清：curl -XPOST 'localhost:9200/<索引>/_delete_by_query' ...）"
