#!/bin/sh
# Start order (8107) in the foreground, connected to the Sentinel dashboard (mh-sentinel, http://127.0.0.1:8858).
# The dashboard runs in a container and connects back to port 8719 on this machine; it only accepts IPs, so resolve host.docker.internal inside the container to get the host IP
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
SENTINEL_CLIENT_IP=$(docker exec mh-sentinel getent hosts host.docker.internal 2>/dev/null | awk '{print $1}' | head -1)
[ -n "$SENTINEL_CLIENT_IP" ] || { echo "mh-sentinel container not found; run docker compose up -d sentinel first"; exit 1; }
export SENTINEL_CLIENT_IP
echo "Sentinel dashboard callback address: $SENTINEL_CLIENT_IP:8719"
exec "$JAVA_HOME/bin/java" -jar OrderService/target/OrderService-0.0.1-SNAPSHOT.jar \
  --merchanthub.order.event-mode=OUTBOX
