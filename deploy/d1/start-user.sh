#!/bin/sh
# D1：前台启动 user-tenant（8104），日志直接打在这个终端里（看审批办理、结果消息投递）
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar UserService/target/UserService-0.0.1-SNAPSHOT.jar
