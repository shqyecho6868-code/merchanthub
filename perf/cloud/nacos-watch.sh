#!/bin/bash
# G-下：故障演练 / 扩容时，在中间件 EC2 上每秒查一次 Nacos 里 order 的实例（IP + healthy），持续 N 秒，只输出发生变化的时刻。
# 用来量"实例被摘除 / 新实例注册"的时刻（order 不直接挂 ALB，gateway 是从 Nacos 拿 order 地址的）
#   ./perf/cloud/nacos-watch.sh [秒数=300] > perf/results/<RUN_ID>/nacos.txt
SECS=${1:-300}
"$(dirname "$0")/remote.sh" "for i in \$(seq 1 $SECS); do
  echo \"\$(TZ=America/Los_Angeles date +%H:%M:%S) \$(curl -s 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=order' | python3 -c 'import json,sys; d=json.load(sys.stdin); h=d.get(\"data\", d.get(\"hosts\")) or []; h=h.get(\"hosts\", []) if isinstance(h, dict) else h; print(\" \".join(sorted(x[\"ip\"]+(\"\" if x.get(\"healthy\", True) else \"(unhealthy)\") for x in h)) or \"(无实例)\")' 2>&1 | tail -1)\"
  sleep 1
done | awk '{k=\$0; sub(/^[^ ]+ /,\"\",k)} k!=p {print; p=k}'"
