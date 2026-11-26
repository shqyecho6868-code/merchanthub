#!/bin/bash
# For load testing: start the 5 services on the trading path in the background (gateway, user-tenant, product-inventory, order, payment); logs in /tmp/mh-perf-*.log.
#   ./perf/start-services.sh            start (run ./mvnw -q package -DskipTests first)
#   ./perf/start-services.sh stop       stop
#   ./perf/start-services.sh status     show each service's health
# Extra per-service startup args go in env vars, e.g. raise the order rate limit for the load-test merchant (default 20 QPS per merchant):
#   ORDER_OPTS='--merchanthub.order.sentinel.rate-limit.tenant-overrides[901]=2000' ./perf/start-services.sh
#   JVM args: JAVA_OPTS='-Xms1g -Xmx1g' ./perf/start-services.sh
cd "$(dirname "$0")/.." || exit 1
. deploy/env.sh

# service name : module : port : extra-args variable name
SERVICES="gateway:GateWay:10010:GATEWAY_OPTS user:UserService:8104:USER_OPTS product:ProductInventoryService:8106:PRODUCT_OPTS order:OrderService:8107:ORDER_OPTS payment:PaymentService:8108:PAYMENT_OPTS"

case "$1" in
  stop)
    pkill -f 'GateWay-0.0.1-SNAPSHOT.jar|UserService-0.0.1-SNAPSHOT.jar|ProductInventoryService-0.0.1-SNAPSHOT.jar|OrderService-0.0.1-SNAPSHOT.jar|PaymentService-0.0.1-SNAPSHOT.jar'
    echo "Stop signal sent"; exit 0 ;;
  status)
    for s in $SERVICES; do
      IFS=: read -r name mod port _ <<< "$s"
      # A 404 still means the service is up (actuator endpoint not exposed)
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health")
      case "$code" in 200) st=UP ;; 000) st=NOT_READY ;; *) st="STARTED (HTTP ${code})" ;; esac
      printf '%-10s %-6s %s\n' "$name" "$port" "$st"
    done
    exit 0 ;;
esac

# order needs a host IP the Sentinel dashboard container can call back (the dashboard rejects hostnames); skipped if the dashboard isn't running (doesn't affect the test)
SENTINEL_CLIENT_IP=$(docker exec mh-sentinel getent hosts host.docker.internal 2>/dev/null | awk '{print $1}' | head -1)
export SENTINEL_CLIENT_IP

for s in $SERVICES; do
  IFS=: read -r name mod port optvar <<< "$s"
  jar="$mod/target/$mod-0.0.1-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "${jar} not found, run ./mvnw -q package -DskipTests first"; exit 1; }
  # shellcheck disable=SC2086
  nohup "$JAVA_HOME/bin/java" ${JAVA_OPTS:-} -jar "$jar" ${!optvar:-} > "/tmp/mh-perf-$name.log" 2>&1 &
  echo "${name} (${port}) starting, log: /tmp/mh-perf-${name}.log"
done
echo "Wait ~30s until ./perf/start-services.sh status shows all UP, then start testing"
