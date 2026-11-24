#!/bin/sh
# E3 演示：后台启动 product-inventory / order / payment（日志在 /tmp/mh-*.log），它们会注册到 XXL-Job 调度中心。
# 停止：./deploy/e3/start-services.sh stop
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
if [ "$1" = "stop" ]; then
  pkill -f 'ProductInventoryService-0.0.1-SNAPSHOT.jar|OrderService-0.0.1-SNAPSHOT.jar|PaymentService-0.0.1-SNAPSHOT.jar'
  exit 0
fi
for m in ProductInventoryService OrderService PaymentService; do
  nohup "$JAVA_HOME/bin/java" -jar "$m/target/$m-0.0.1-SNAPSHOT.jar" > "/tmp/mh-$m.log" 2>&1 &
  echo "$m 启动中，日志 /tmp/mh-$m.log"
done
