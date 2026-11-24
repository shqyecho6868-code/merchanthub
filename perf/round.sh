#!/bin/bash
# F1：跑"一轮"完整压测 = 重置数据 → 清 SQL 统计 → 后台采样 → k6 → 停采样 → 数据检查 → 热点 SQL 报告。
#   ./perf/round.sh <RUN_ID> <k6 脚本> [k6 参数]
#   HOT_STOCK=500 ./perf/round.sh s2b perf/k6/scenarios/s2b-sellout.js
# 产物都在 perf/results/<RUN_ID>/：summary.md / summary.json / mysql.csv / mq.csv / top-sql.txt / check.txt
cd "$(dirname "$0")/.." || exit 1
RUN_ID=$1; SCRIPT=$2; shift 2 || { echo "用法：$0 <RUN_ID> <k6 脚本> [k6 参数]"; exit 1; }
DIR=perf/results/$RUN_ID
rm -rf "$DIR"; mkdir -p "$DIR"

./perf/data/seed.sh > "$DIR/seed.txt" 2>&1 || { cat "$DIR/seed.txt"; exit 1; }
./perf/monitor/top-sql.sh reset > /dev/null
./perf/monitor/sample.sh "$RUN_ID" 5 > /dev/null 2>&1 &
SAMPLER=$!
RUN_ID=$RUN_ID ./perf/run.sh "$SCRIPT" "$@" > "$DIR/k6.txt" 2>&1
K6_EXIT=$?
sleep 5; kill "$SAMPLER" 2>/dev/null
./perf/check/check.sh "$RUN_ID" 90 > "$DIR/check.txt" 2>&1
CHECK_EXIT=$?
./perf/monitor/top-sql.sh report "$RUN_ID" > /dev/null 2>&1

cat "$DIR/summary.md" 2>/dev/null || tail -20 "$DIR/k6.txt"
echo; grep -E "PASS|FAIL|INFO|剩余" "$DIR/check.txt" | sed 's/\x1b\[[0-9;]*m//g'
echo; echo "k6 退出码 ${K6_EXIT}（阈值未达标时非 0），数据检查退出码 ${CHECK_EXIT}；明细在 $DIR/"
