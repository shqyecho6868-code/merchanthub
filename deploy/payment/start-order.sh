#!/bin/sh
# Experiments: start order (8107, outbox mode) in the foreground; logs go to this terminal.
#   Arg 1: payment deadline (default 15m; experiment 2 uses 20s)
#   Arg 2: milliseconds to pause after inventory reservation and before persisting the order (default 0; experiment 1 uses 40000)
#   ./deploy/payment/start-order.sh 15m 40000   -> experiment 1
#   ./deploy/payment/start-order.sh 20s         -> experiment 2
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=OUTBOX \
  --merchanthub.order.pay-timeout="${1:-15m}" \
  --merchanthub.order.debug.pause-before-persist-ms="${2:-0}"
