#!/bin/sh
# Demo: start order in the foreground with Canal -> ES sync enabled (needs canal and elasticsearch up via docker compose)
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar --merchanthub.order.canal.enabled=true
