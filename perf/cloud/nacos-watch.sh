#!/bin/bash
# During failure drills / scale-out: on the middleware EC2, query order's instances in Nacos (IP + healthy) every second
# for N seconds, printing only the moments when they change.
# Measures when an instance is removed / a new one registers (order is not behind the ALB; gateway gets order's address from Nacos)
#   ./perf/cloud/nacos-watch.sh [seconds=300] > perf/results/<RUN_ID>/nacos.txt
SECS=${1:-300}
"$(dirname "$0")/remote.sh" "for i in \$(seq 1 $SECS); do
  echo \"\$(TZ=America/Los_Angeles date +%H:%M:%S) \$(curl -s 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=order' | python3 -c 'import json,sys; d=json.load(sys.stdin); h=d.get(\"data\", d.get(\"hosts\")) or []; h=h.get(\"hosts\", []) if isinstance(h, dict) else h; print(\" \".join(sorted(x[\"ip\"]+(\"\" if x.get(\"healthy\", True) else \"(unhealthy)\") for x in h)) or \"(no instances)\")' 2>&1 | tail -1)\"
  sleep 1
done | awk '{k=\$0; sub(/^[^ ]+ /,\"\",k)} k!=p {print; p=k}'"
