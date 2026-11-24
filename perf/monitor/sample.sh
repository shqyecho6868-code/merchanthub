#!/bin/bash
# F1：压测期间每隔几秒采样一次"看瓶颈"用的指标，写 CSV，压完和 k6 的延迟曲线对着看。
#   ./perf/monitor/sample.sh <RUN_ID> [间隔秒=5] &      # 压测前后台启动；压完 kill %1（或 Ctrl-C）
# 输出到 perf/results/<RUN_ID>/：
#   mysql.csv  连接数、正在执行的线程、行锁等待（库存热点行锁是下单链路最可能的瓶颈）
#   mq.csv     各消费组堆积（Diff Total）：堆积持续上涨 = 消费跟不上生产，异步扣减 / 推送会越来越晚
# 应用侧连接池（Hikari active / pending）、各接口 P99 看 Prometheus / Grafana（docker compose --profile perf up -d）
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
RUN_ID=${1:?用法：$0 <RUN_ID> [间隔秒]}
INTERVAL=${2:-5}
DIR=perf/results/$RUN_ID; mkdir -p "$DIR"

echo "time,threads_connected,threads_running,row_lock_current_waits,row_lock_waits_total,row_lock_time_ms_total,slow_queries_total" > "$DIR/mysql.csv"
echo "time,group,diff_total" > "$DIR/mq.csv"
echo "采样中（每 ${INTERVAL}s）→ $DIR/mysql.csv、$DIR/mq.csv；Ctrl-C 结束"

trap 'echo; echo "采样结束"; exit 0' INT TERM
while true; do
  NOW=$(date +%H:%M:%S)
  # SHOW GLOBAL STATUS 的计数器是累计值，看曲线时取相邻两行的差
  row=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -N -e "
    SELECT GROUP_CONCAT(VARIABLE_VALUE ORDER BY FIELD(VARIABLE_NAME,
      'Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries'))
    FROM performance_schema.global_status WHERE VARIABLE_NAME IN
      ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries')" 2>/dev/null)
  echo "$NOW,$row" >> "$DIR/mysql.csv"
  # 不带 -g 一次列出所有消费组（每次调用都要起一个 JVM，约 3 秒，按组逐个查会拖慢采样）；最后一列是 Diff Total
  docker exec mh-broker sh -c 'cd /home/rocketmq/rocketmq-*/bin && ./mqadmin consumerProgress -n namesrv:9876 2>/dev/null' \
    | awk -v t="$NOW" 'NR > 1 && $1 !~ /^#/ && NF >= 3 && $1 !~ /^(%|CID_|TOOLS_)/ {print t "," $1 "," $NF}' >> "$DIR/mq.csv"
  sleep "$INTERVAL"
done
