#!/bin/sh
# C2 🧑 实验：前台启动 order（8107），并接入 Sentinel 控制台（mh-sentinel，http://127.0.0.1:8858）。
# 控制台在容器里，要回连本机 8719；它只接受 IP，所以先在容器里解析 host.docker.internal 得到宿主机 IP（P30）
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
SENTINEL_CLIENT_IP=$(docker exec mh-sentinel getent hosts host.docker.internal 2>/dev/null | awk '{print $1}' | head -1)
[ -n "$SENTINEL_CLIENT_IP" ] || { echo "找不到 mh-sentinel 容器，先 docker compose up -d sentinel"; exit 1; }
export SENTINEL_CLIENT_IP
echo "Sentinel 控制台回连地址：$SENTINEL_CLIENT_IP:8719"
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=OUTBOX
