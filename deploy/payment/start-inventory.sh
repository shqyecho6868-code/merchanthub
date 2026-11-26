#!/bin/sh
# Start product-inventory (8106) in the foreground; logs go to this terminal (watch confirm-deduct / release)
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
exec "$JAVA_HOME/bin/java" -jar ProductInventoryService/target/ProductInventoryService-0.0.1-SNAPSHOT.jar
