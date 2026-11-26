package com.merchanthub.realtime.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.common.trace.TraceContext;
import com.merchanthub.realtime.config.NodeId;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/** The two realtime consumers */
@Slf4j
@Configuration
public class RealtimeConsumersConfig {

    /**
     * (1) Order events (clustering mode, all nodes in one group): any node consuming it -> records the notice -> dispatches via the route table.
     * The consumer has nothing to do with which node the connection is on, which is exactly why a route table + forwarding are needed
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "merchanthub.realtime.order-consumer.enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQPushConsumer realtimeOrderEventConsumer(
            OrderNotifyService notifyService, PushDispatcher dispatcher, ObjectMapper objectMapper, NodeId nodeId,
            @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
            @Value("${merchanthub.realtime.order-consumer.consume-from:CONSUME_FROM_LAST_OFFSET}") ConsumeFromWhere from)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(PushTopics.ORDER_CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(from);
        consumer.setInstanceName("realtime-order-" + nodeId.value());
        consumer.subscribe(PushTopics.ORDER_TOPIC, String.join(" || ",
                PushTopics.TAG_ORDER_CREATED, PushTopics.TAG_ORDER_PAID, PushTopics.TAG_ORDER_CLOSED));
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                // traceId: restore the sender's trace ID so every log for this message (including the retry warn below) carries it; restore afterwards so reused consumer threads don't leak it
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    JsonNode event;
                    try {
                        event = objectMapper.readTree(m.getBody());
                    } catch (IOException e) {
                        log.error("无法解析的订单事件，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                        continue;
                    }
                    List<PushMessage> toPush;
                    try {
                        toPush = notifyService.record(m.getTags(), event);
                    } catch (RuntimeException e) {
                        // Database unavailable: the dedup record rolls back with the transaction, so a later redelivery can process it again
                        log.warn("处理订单事件失败，稍后重投 tag={} keys={} reconsumeTimes={}", m.getTags(), m.getKeys(), m.getReconsumeTimes(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                    for (PushMessage p : toPush) {
                        try {
                            dispatcher.dispatch(p);
                        } catch (RuntimeException e) {
                            // Dedup is already recorded, so a redelivery won't push again: all we can do is log (the notice itself is persisted; the merchant can still see it in the notification center)
                            log.error("推送分发失败 receiverKey={} msgId={}", p.receiverKey(), p.msgId(), e);
                        }
                    }
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("订单事件消费者已启动 group={} node={}", PushTopics.ORDER_CONSUMER_GROUP, nodeId);
        return consumer;
    }

    /**
     * (2) Node-specific forwarding consumer: group name realtime-push-{nodeId}, subscribes only to Tag = its own node ID.
     * Starts from the earliest offset: when a group for a new node ID starts for the first time, the Topic holds no old messages for it; when a node restarts with the same ID it reuses the committed offset,
     * so messages forwarded to it while it was down are redelivered (by then the receiver is most likely no longer on this node -> left for redelivery on coming online)
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer realtimeNodePushConsumer(
            LocalDelivery local, ObjectMapper objectMapper, NodeId nodeId,
            @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(PushTopics.nodeConsumerGroup(nodeId.value()));
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setInstanceName("realtime-push-" + nodeId.value());
        consumer.subscribe(PushTopics.PUSH_TOPIC, nodeId.value());
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                // traceId: restore the sender's trace ID so every log for this message (including the retry warn below) carries it; restore afterwards so reused consumer threads don't leak it
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    try {
                        local.deliver(objectMapper.readValue(m.getBody(), PushMessage.class));
                    } catch (IOException e) {
                        log.error("无法解析的转发消息，丢弃 msgId={}", m.getMsgId(), e);
                    } catch (RuntimeException e) {
                        log.warn("本地投递失败（消息仍是待送达，上线补发）msgId={}", m.getMsgId(), e);
                    }
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("节点转发消费者已启动 group={} tag={}", PushTopics.nodeConsumerGroup(nodeId.value()), nodeId);
        return consumer;
    }
}
