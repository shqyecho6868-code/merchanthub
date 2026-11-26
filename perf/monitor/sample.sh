#!/bin/bash
# Sample bottleneck metrics every few seconds during a load test into CSV, to compare with k6's latency curve afterwards.
#   ./perf/monitor/sample.sh <RUN_ID> [interval seconds=5] &      # start in background before the test; kill %1 (or Ctrl-C) after
# Output in perf/results/<RUN_ID>/:
#   mysql.csv  connections, running threads, row lock waits (hot-row locks on inventory are the most likely bottleneck when ordering)
#   mq.csv     per consumer group backlog (Diff Total): a steadily growing backlog = consumers can't keep up, async deduction / push gets later and later
# App-side pool (Hikari active / pending) and per-endpoint P99 are in Prometheus / Grafana (docker compose --profile perf up -d)
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
RUN_ID=${1:?Usage: $0 <RUN_ID> [interval seconds]}
INTERVAL=${2:-5}
DIR=perf/results/$RUN_ID; mkdir -p "$DIR"

echo "time,threads_connected,threads_running,row_lock_current_waits,row_lock_waits_total,row_lock_time_ms_total,slow_queries_total" > "$DIR/mysql.csv"
echo "time,group,diff_total" > "$DIR/mq.csv"
echo "Sampling (every ${INTERVAL}s) -> $DIR/mysql.csv, $DIR/mq.csv; Ctrl-C to stop"

trap 'echo; echo "Sampling stopped"; exit 0' INT TERM
while true; do
  NOW=$(date +%H:%M:%S)
  # SHOW GLOBAL STATUS counters are cumulative; take the difference between adjacent rows when plotting
  row=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N -e "
    SELECT GROUP_CONCAT(VARIABLE_VALUE ORDER BY FIELD(VARIABLE_NAME,
      'Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries'))
    FROM performance_schema.global_status WHERE VARIABLE_NAME IN
      ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries')" 2>/dev/null)
  echo "$NOW,$row" >> "$DIR/mysql.csv"
  # Without -g it lists all consumer groups at once (each call starts a JVM, ~3s; querying group by group would slow sampling); last column is Diff Total
  docker exec mh-broker sh -c 'cd /home/rocketmq/rocketmq-*/bin && ./mqadmin consumerProgress -n namesrv:9876 2>/dev/null' \
    | awk -v t="$NOW" 'NR > 1 && $1 !~ /^#/ && NF >= 3 && $1 !~ /^(%|CID_|TOOLS_)/ {print t "," $1 "," $NF}' >> "$DIR/mq.csv"
  sleep "$INTERVAL"
done
