#!/bin/bash
# F1：慢 SQL / 热点 SQL。用 performance_schema 的语句摘要表（按"去掉参数后的 SQL 模板"聚合），比翻慢日志直观。
#   ./perf/monitor/top-sql.sh reset              压测前：清空统计 + 打开慢日志（> SLOW_MS 毫秒，默认 100）
#   ./perf/monitor/top-sql.sh report [RUN_ID]    压测后：按总耗时 / 平均耗时各列 Top 10，并统计慢日志条数
# 慢日志文件在容器里：docker exec mh-mysql tail -f /var/lib/mysql/slow.log
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
q() { docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mh-mysql mysql -uroot -t -e "$1"; }
SLOW_MS=${SLOW_MS:-100}

case "$1" in
  reset)
    q "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
       SET GLOBAL slow_query_log_file = '/var/lib/mysql/slow.log';
       SET GLOBAL long_query_time = $SLOW_MS / 1000;
       SET GLOBAL slow_query_log = ON;
       SELECT @@GLOBAL.slow_query_log AS slow_log_on, @@GLOBAL.long_query_time AS long_query_time_s;"
    echo "已清空语句统计、打开慢日志（新连接生效；long_query_time 是会话级继承，已有连接池连接需重启服务才用新阈值）"
    ;;
  report)
    report() {
      echo "== 按总耗时 Top 10（谁最占数据库时间）=="
      q "SELECT SCHEMA_NAME db, LEFT(DIGEST_TEXT, 90) sql_template, COUNT_STAR calls,
           ROUND(SUM_TIMER_WAIT / 1e9) total_ms, ROUND(AVG_TIMER_WAIT / 1e9, 2) avg_ms, ROUND(MAX_TIMER_WAIT / 1e9, 1) max_ms,
           ROUND(SUM_LOCK_TIME / 1e9) lock_ms, SUM_ROWS_EXAMINED rows_examined
         FROM performance_schema.events_statements_summary_by_digest
         WHERE SCHEMA_NAME LIKE 'mh\\_%' ORDER BY SUM_TIMER_WAIT DESC LIMIT 10"
      echo "== 按平均耗时 Top 10（单条最慢）=="
      q "SELECT SCHEMA_NAME db, LEFT(DIGEST_TEXT, 90) sql_template, COUNT_STAR calls, ROUND(AVG_TIMER_WAIT / 1e9, 2) avg_ms,
           SUM_NO_INDEX_USED no_index
         FROM performance_schema.events_statements_summary_by_digest
         WHERE SCHEMA_NAME LIKE 'mh\\_%' AND COUNT_STAR > 10 ORDER BY AVG_TIMER_WAIT DESC LIMIT 10"
      echo "== 慢日志 =="
      docker exec mh-mysql sh -c "grep -c '^# Query_time' /var/lib/mysql/slow.log 2>/dev/null || echo 0" | sed 's/^/慢查询条数（> '"$SLOW_MS"'ms）：/'
    }
    if [ -n "$2" ]; then mkdir -p "perf/results/$2"; report | tee "perf/results/$2/top-sql.txt"; else report; fi
    ;;
  *) echo "用法：$0 reset | report [RUN_ID]"; exit 1 ;;
esac
