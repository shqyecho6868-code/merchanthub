#!/bin/bash
# Run before every load-test round: (1) delete all business data the load-test merchants (901 / 902) produced last round
# (2) load / reset the base data in seed.sql.
# Cleanup is dynamic: in every mh_* schema except mh_user, every table with a tenant_id column has its tenant_id IN (901, 902) rows deleted;
# only product, sku and inventory are kept (seed.sql resets them). New tables get cleaned automatically.
#   ./perf/data/seed.sh
#   HOT_STOCK=500 ./perf/data/seed.sh     # S2b: set only SKU 90101's stock to 500 (sell-out scenario)
# The initial stock is recorded in perf/results/seed-baseline.env; check.sh reconciles against it
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
TENANTS="901,902"
sql() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N "$@"; }

echo "Cleaning old data of load-test merchants $TENANTS:"
TABLES=$(sql -e "SELECT CONCAT(table_schema, '.', table_name) FROM information_schema.columns
  WHERE column_name = 'tenant_id' AND table_schema LIKE 'mh\\_%' AND table_schema <> 'mh_user'
    AND table_name NOT IN ('product', 'sku', 'inventory')")
for t in $TABLES; do
  n=$(sql -e "DELETE FROM $t WHERE tenant_id IN ($TENANTS); SELECT ROW_COUNT();")
  [ "$n" != "0" ] && echo "  ${t}: deleted ${n} rows"
done

echo "Loading base data (merchants, accounts, products, SKUs, stock reset)"
sql < perf/data/seed.sql || exit 1
if [ -n "$HOT_STOCK" ]; then
  sql -e "UPDATE mh_product.inventory SET available = $HOT_STOCK WHERE sku_id = 90101"
  echo "SKU 90101 stock set to $HOT_STOCK"
fi
mkdir -p perf/results && echo "HOT_STOCK=${HOT_STOCK}" > perf/results/seed-baseline.env
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "SELECT tenant_id, COUNT(*) skus, SUM(available) available, SUM(reserved) reserved
  FROM mh_product.inventory WHERE tenant_id IN ($TENANTS) GROUP BY tenant_id;
  SELECT COUNT(*) buyers FROM mh_user.account WHERE username LIKE 'perf_buyer_%';"
echo "Done. Old order documents in ES don't affect the test (clear them manually if a search scenario needs it: curl -XPOST 'localhost:9200/<index>/_delete_by_query' ...)"
