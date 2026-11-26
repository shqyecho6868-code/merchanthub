#!/bin/sh
# Experiment 1: the "payment succeeded" event arrives before the order is persisted.
#   Usage: ./deploy/b3/early-pay.sh <orderNo> <amount>
# Sends a PAY_SUCCESS straight to PAYMENT_EVENT (simulating payment having confirmed the money; in the real flow payment looks up the order before creating a payment, so this ordering can't be produced)
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
O=$1; A=$2
[ -n "$O" ] && [ -n "$A" ] || { echo "Usage: $0 <orderNo> <amount>"; exit 1; }
P=$(date +%s)000
BODY="{\"eventId\":$P,\"payNo\":$P,\"orderNo\":$O,\"tenantId\":7,\"buyerId\":1001,\"amount\":$A,\"channelTradeNo\":\"EARLY-$P\",\"paidAt\":\"$(date -u +%Y-%m-%dT%H:%M:%S)\"}"
docker exec mh-broker sh -c "cd /home/rocketmq/rocketmq-*/bin && ./mqadmin sendMessage -n namesrv:9876 -t PAYMENT_EVENT -c PAY_SUCCESS -k '$O $P' -p '$BODY'" \
  | grep -q SEND_OK && echo "Sent payment-success event payNo=$P orderNo=$O amount=$A (order not persisted yet)" || { echo "Send failed"; exit 1; }
