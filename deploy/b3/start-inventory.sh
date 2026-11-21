#!/bin/sh
# B3-下：前台启动 product-inventory（8106），日志直接打在这个终端里（看确认扣减 / 释放）
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar ProductInventoryService/target/ProductInventoryService-0.0.1-SNAPSHOT.jar
