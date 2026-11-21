#!/bin/sh
# B3-下 🧑 实验：前台启动 order（8107，本地消息表模式），日志直接打在这个终端里。
#   参数 1：支付时限（默认 15m；实验 ② 用 20s）
#   参数 2：库存预占后、订单落库前停顿多少毫秒（默认 0；实验 ① 用 40000）
#   ./deploy/b3/start-order.sh 15m 40000   → 实验 ①
#   ./deploy/b3/start-order.sh 20s         → 实验 ②
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=OUTBOX \
  --merchanthub.order.pay-timeout="${1:-15m}" \
  --merchanthub.order.debug.pause-before-persist-ms="${2:-0}"
