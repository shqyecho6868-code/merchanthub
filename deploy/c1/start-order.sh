#!/bin/sh
# Start order (8107) in the foreground with mapper debug logging -- every SQL is printed as
#   ==>  Preparing: SELECT ... WHERE (order_no = ?) AND tenant_id = 7
# so you can see the condition appended by the tenant plugin
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --logging.level.com.merchanthub.order.mapper=debug
