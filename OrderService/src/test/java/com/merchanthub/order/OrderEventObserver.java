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
 * 测试里的"旁观者"消费者：用一个随机的独立消费组订阅 ORDER_EVENT，把收到的消息按订单号记下来，
 * 用来断言"消费者到底能不能看到这条消息"。它不做幂等，所以重复投递也会如实记录。
 */
final class OrderEventObserver implements AutoCloseable {

    private final DefaultMQPushConsumer consumer;
    private final ConcurrentHashMap<Long, List<MessageExt>> byOrderNo = new ConcurrentHashMap<>();

    OrderEventObserver() throws MQClientException {
        consumer = new DefaultMQPushConsumer("it-observer-" + UUID.randomUUID().toString().substring(0, 8));
        consumer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        consumer.setVipChannelEnabled(false);
        // 新消费组从头读：避免"消费者还没完成负载均衡，消息已经发了"导致漏收
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
