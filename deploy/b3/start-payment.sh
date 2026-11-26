#!/bin/sh
# Start the payment service (8108) in the foreground; logs go to this terminal
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar PaymentService/target/PaymentService-0.0.1-SNAPSHOT.jar
