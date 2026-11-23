#!/bin/sh
# C1 🧑 实验：前台启动 order（8107），打开 mapper 的 debug 日志 —— 每条 SQL 打印成
#   ==>  Preparing: SELECT ... WHERE (order_no = ?) AND tenant_id = 7
# 能直接看到租户插件拼上去的条件
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --logging.level.com.merchanthub.order.mapper=debug
