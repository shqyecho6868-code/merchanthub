#!/bin/sh
# 预先创建 B2/B3 用到的 Topic（不依赖 autoCreateTopicEnable：自动建的 Topic 消费者最多 30 秒后才发现，P20）。可重复执行
for T in ORDER_EVENT ORDER_TIMEOUT PAYMENT_EVENT; do
  docker exec mh-broker sh -c "cd /home/rocketmq/rocketmq-*/bin && ./mqadmin updateTopic -n namesrv:9876 -c DefaultCluster -t $T" \
    | grep -q success && echo "Topic $T 已就绪" || echo "Topic $T 创建失败"
done
