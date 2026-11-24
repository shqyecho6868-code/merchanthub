#!/bin/sh
# E2 演示：前台启动 order，打开 Canal → ES 同步（需要 docker compose 起好 canal、elasticsearch）
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar --merchanthub.order.canal.enabled=true
