#!/bin/sh
# C1 🧑 实验：前台启动网关（10010）。外部请求统一走 http://localhost:10010/api/...
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar GateWay/target/GateWay-0.0.1-SNAPSHOT.jar
