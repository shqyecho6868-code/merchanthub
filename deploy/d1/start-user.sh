#!/bin/sh
# Start user-tenant (8104) in the foreground; logs go to this terminal (watch task handling and result message delivery)
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
exec "$JAVA_HOME/bin/java" -jar UserService/target/UserService-0.0.1-SNAPSHOT.jar
