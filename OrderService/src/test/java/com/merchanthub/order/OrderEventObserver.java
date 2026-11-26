package com.merchanthub.order;

import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An "observer" consumer for tests: subscribes to ORDER_EVENT with a random separate consumer group and records received messages by order number,
 * used to assert "can a consumer actually see this message". It isn't idempotent, so redeliveries are recorded faithfully.
 */
final class OrderEventObserver implements AutoCloseable {

    private final DefaultMQPushConsumer consumer;
    private final ConcurrentHashMap<Long, List<MessageExt>> byOrderNo = new ConcurrentHashMap<>();

    OrderEventObserver() throws MQClientException {
        consumer = new DefaultMQPushConsumer("it-observer-" + UUID.randomUUID().toString().substring(0, 8));
        consumer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        consumer.setVipChannelEnabled(false);
        // A new consumer group reads from the beginning: avoids missing messages sent "before the consumer finished rebalancing"
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(OrderEventTopics.TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                String orderNo = m.getKeys().split(" ")[0];
                byOrderNo.computeIfAbsent(Long.parseLong(orderNo), k -> new CopyOnWriteArrayList<>()).add(m);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    List<MessageExt> received(long orderNo) {
        return byOrderNo.getOrDefault(orderNo, List.of());
    }

    String body(MessageExt m) {
        return new String(m.getBody(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        consumer.shutdown();
    }
}
