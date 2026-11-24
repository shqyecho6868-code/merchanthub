#!/bin/bash
# F1：压测用，一次后台启动交易链路 5 个服务（网关、user-tenant、product-inventory、order、payment），日志 /tmp/mh-perf-*.log。
#   ./perf/start-services.sh            启动（先 ./mvnw -q package -DskipTests）
#   ./perf/start-services.sh stop       停止
#   ./perf/start-services.sh status     看各服务 health
# 每个服务额外的启动参数用环境变量传，例如给压测商家提高下单限流阈值（默认每商家 20 QPS，见 C2）：
#   ORDER_OPTS='--merchanthub.order.sentinel.rate-limit.tenant-overrides[901]=2000' ./perf/start-services.sh
#   JVM 参数：JAVA_OPTS='-Xms1g -Xmx1g' ./perf/start-services.sh
cd "$(dirname "$0")/.." || exit 1
. deploy/b2/env.sh

# 服务名 模块 端口 额外参数变量名
SERVICES="gateway:GateWay:10010:GATEWAY_OPTS user:UserService:8104:USER_OPTS product:ProductInventoryService:8106:PRODUCT_OPTS order:OrderService:8107:ORDER_OPTS payment:PaymentService:8108:PAYMENT_OPTS"

case "$1" in
  stop)
    pkill -f 'GateWay-0.0.1-SNAPSHOT.jar|UserService-0.0.1-SNAPSHOT.jar|ProductInventoryService-0.0.1-SNAPSHOT.jar|OrderService-0.0.1-SNAPSHOT.jar|PaymentService-0.0.1-SNAPSHOT.jar'
    echo "已发送停止信号"; exit 0 ;;
  status)
    for s in $SERVICES; do
      IFS=: read -r name mod port _ <<< "$s"
      # user-tenant 没接 actuator（不在压测热路径上），返回 404 也说明已启动
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health")
      case "$code" in 200) st=UP ;; 000) st=未就绪 ;; *) st="已启动（HTTP ${code}）" ;; esac
      printf '%-10s %-6s %s\n' "$name" "$port" "$st"
    done
    exit 0 ;;
esac

# order 接 Sentinel 控制台需要容器能回连的宿主机 IP（P30），控制台没起就不接（不影响压测）
SENTINEL_CLIENT_IP=$(docker exec mh-sentinel getent hosts host.docker.internal 2>/dev/null | awk '{print $1}' | head -1)
export SENTINEL_CLIENT_IP

for s in $SERVICES; do
  IFS=: read -r name mod port optvar <<< "$s"
  jar="$mod/target/$mod-0.0.1-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "找不到 ${jar}，先 ./mvnw -q package -DskipTests"; exit 1; }
  # shellcheck disable=SC2086
  nohup "$JAVA_HOME/bin/java" ${JAVA_OPTS:-} -jar "$jar" ${!optvar:-} > "/tmp/mh-perf-$name.log" 2>&1 &
  echo "${name}（${port}）启动中，日志 /tmp/mh-perf-${name}.log"
done
echo "约 30 秒后 ./perf/start-services.sh status 全部 UP 再压测"
