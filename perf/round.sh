#!/bin/bash
# Run one full load-test round = reset data -> reset SQL stats -> background sampling -> k6 -> stop sampling -> data check -> hot SQL report.
#   ./perf/round.sh <RUN_ID> <k6 script> [k6 args]
#   HOT_STOCK=500 ./perf/round.sh s2b perf/k6/scenarios/s2b-sellout.js
# Output goes to perf/results/<RUN_ID>/: summary.md / summary.json / mysql.csv / mq.csv / top-sql.txt / check.txt
cd "$(dirname "$0")/.." || exit 1
RUN_ID=$1; SCRIPT=$2; shift 2 || { echo "Usage: $0 <RUN_ID> <k6 script> [k6 args]"; exit 1; }
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
echo; grep -E "PASS|FAIL|INFO|remaining" "$DIR/check.txt" | sed 's/\x1b\[[0-9;]*m//g'
echo; echo "k6 exit code ${K6_EXIT} (non-zero when thresholds fail), data check exit code ${CHECK_EXIT}; details in $DIR/"
