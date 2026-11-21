#!/bin/sh
# B3：前台启动 payment 服务（8108），日志直接打在这个终端里
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar PaymentService/target/PaymentService-0.0.1-SNAPSHOT.jar
