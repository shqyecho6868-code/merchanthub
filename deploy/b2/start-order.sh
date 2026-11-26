#!/bin/sh
# Kill experiment: start the order service in the foreground (logs go to this terminal).
# Arg 1: milliseconds to pause after the local commit and before committing the message (default 0 = normal run).
#   ./deploy/b2/start-order.sh 30000   -> leaves 30 seconds to kill it
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
PAUSE=${1:-0}
echo "$$" > /tmp/mh-order.pid   # this script execs into java, so the PID stays the same; used for kill
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=TX_MESSAGE \
  --merchanthub.order.tx.debug.pause-after-local-commit-ms="$PAUSE"
