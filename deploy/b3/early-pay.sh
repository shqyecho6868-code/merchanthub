#!/bin/sh
# B3-下 🧑 实验 ①：订单还没落库，"支付成功"事件就到了。
#   用法：./deploy/b3/early-pay.sh 订单号 金额
# 直接往 PAYMENT_EVENT 发一条 PAY_SUCCESS（模拟 payment 已确认收款；真实链路里 payment 建单要先查订单，造不出这个时序）
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
O=$1; A=$2
[ -n "$O" ] && [ -n "$A" ] || { echo "用法：$0 订单号 金额"; exit 1; }
P=$(date +%s)000
BODY="{\"eventId\":$P,\"payNo\":$P,\"orderNo\":$O,\"tenantId\":7,\"buyerId\":1001,\"amount\":$A,\"channelTradeNo\":\"EARLY-$P\",\"paidAt\":\"$(date -u +%Y-%m-%dT%H:%M:%S)\"}"
docker exec mh-broker sh -c "cd /home/rocketmq/rocketmq-*/bin && ./mqadmin sendMessage -n namesrv:9876 -t PAYMENT_EVENT -c PAY_SUCCESS -k '$O $P' -p '$BODY'" \
  | grep -q SEND_OK && echo "已发送支付成功事件 payNo=$P orderNo=$O amount=$A（此时订单还没落库）" || { echo "发送失败"; exit 1; }
