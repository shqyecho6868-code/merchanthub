#!/bin/sh
# Pre-create the topics the services use (don't rely on autoCreateTopicEnable: consumers may take up to 30 seconds to discover an auto-created topic). Safe to re-run
for T in ORDER_EVENT ORDER_TIMEOUT PAYMENT_EVENT PRODUCT_LISTING_RESULT; do
  docker exec mh-broker sh -c "cd /home/rocketmq/rocketmq-*/bin && ./mqadmin updateTopic -n namesrv:9876 -c DefaultCluster -t $T" \
    | grep -q success && echo "Topic $T ready" || echo "Failed to create topic $T"
done
