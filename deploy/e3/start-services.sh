#!/bin/sh
# Demo: start product-inventory / order / payment in the background (logs in /tmp/mh-*.log); they register with the XXL-Job admin.
# Stop: ./deploy/e3/start-services.sh stop
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
if [ "$1" = "stop" ]; then
  pkill -f 'ProductInventoryService-0.0.1-SNAPSHOT.jar|OrderService-0.0.1-SNAPSHOT.jar|PaymentService-0.0.1-SNAPSHOT.jar'
  exit 0
fi
for m in ProductInventoryService OrderService PaymentService; do
  nohup "$JAVA_HOME/bin/java" -jar "$m/target/$m-0.0.1-SNAPSHOT.jar" > "/tmp/mh-$m.log" 2>&1 &
  echo "Starting $m, log at /tmp/mh-$m.log"
done
