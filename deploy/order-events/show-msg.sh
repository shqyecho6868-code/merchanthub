#!/bin/sh
# Look up messages in the broker by order number (mqadmin starts a JVM each time, expect 5-10 seconds)
#   ./deploy/order-events/show-msg.sh <orderNo>
# Searches both the half-message system topic and the business topic, printing topic and properties
ORDER_NO=$1
for T in RMQ_SYS_TRANS_HALF_TOPIC ORDER_EVENT; do
  echo "===== Searching $T for order $ORDER_NO ====="
  ID=$(docker exec mh-broker sh mqadmin queryMsgByKey -n namesrv:9876 -t "$T" -k "$ORDER_NO" 2>/dev/null \
       | awk 'NR>1 && $1 ~ /^[0-9A-F]+$/ {print $1; exit}')
  if [ -z "$ID" ]; then
    echo "(not found)"
  else
    docker exec mh-broker sh mqadmin queryMsgByUniqueKey -n namesrv:9876 -t "$T" -i "$ID" 2>/dev/null \
      | grep -E "^Topic|^Properties" | sed 's/, /\n    /g'
  fi
done
