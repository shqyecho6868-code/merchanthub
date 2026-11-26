#!/bin/sh
# Start the gateway (10010) in the foreground. External requests all go through http://localhost:10010/api/...
cd "$(dirname "$0")/../.." || exit 1
. deploy/env.sh
exec "$JAVA_HOME/bin/java" -jar GateWay/target/GateWay-0.0.1-SNAPSHOT.jar
