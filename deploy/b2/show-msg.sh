#!/bin/sh
# 按订单号在 Broker 里查消息（mqadmin 每次都要启动 JVM，要等 5~10 秒）
#   ./deploy/b2/show-msg.sh <订单号>
# 分别在半消息系统 Topic 和业务 Topic 里查，打印 Topic 和属性
ORDER_NO=$1
for T in RMQ_SYS_TRANS_HALF_TOPIC ORDER_EVENT; do
  echo "===== 在 $T 里查订单号 $ORDER_NO ====="
  ID=$(docker exec mh-broker sh mqadmin queryMsgByKey -n namesrv:9876 -t "$T" -k "$ORDER_NO" 2>/dev/null \
       | awk 'NR>1 && $1 ~ /^[0-9A-F]+$/ {print $1; exit}')
  if [ -z "$ID" ]; then
    echo "（查不到）"
  else
    docker exec mh-broker sh mqadmin queryMsgByUniqueKey -n namesrv:9876 -t "$T" -i "$ID" 2>/dev/null \
      | grep -E "^Topic|^Properties" | sed 's/, /\n    /g'
  fi
done
