#!/bin/sh
# B2 kill 实验：前台启动 order 服务（日志直接打在这个终端里）。
# 参数 1：本地事务提交后、提交消息前停顿多少毫秒（默认 0 = 正常运行）。
#   ./deploy/b2/start-order.sh 30000   → 留 30 秒给你 kill
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
PAUSE=${1:-0}
echo "$$" > /tmp/mh-order.pid   # 本脚本 exec 成 java，PID 不变，kill 用
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=TX_MESSAGE \
  --merchanthub.order.tx.debug.pause-after-local-commit-ms="$PAUSE"
