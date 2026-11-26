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

/** realtime 的两个消费者 */
@Slf4j
@Configuration
public class RealtimeConsumersConfig {

    /**
     * ① 订单事件（集群消费，所有节点同组）：任意一台节点消费到 → 记通知 → 按路由表分发。
     * 消费者跟连接在哪台节点无关，这正是要有路由表 + 转发的原因
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
                // traceId：恢复发送方的链路 ID，本条消息的所有日志（含下面失败重投的 warn）都带上；处理完还原，消费线程复用不串
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
                        // 数据库不可用：去重记录随事务回滚，稍后重投还能重新处理
                        log.warn("处理订单事件失败，稍后重投 tag={} keys={} reconsumeTimes={}", m.getTags(), m.getKeys(), m.getReconsumeTimes(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                    for (PushMessage p : toPush) {
                        try {
                            dispatcher.dispatch(p);
                        } catch (RuntimeException e) {
                            // 已经记过去重了，重投也不会再推：只能记日志（通知本身已落库，商家打开通知中心仍能看到）
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
     * ② 节点专属转发消费者：组名 realtime-push-{节点ID}，只订阅 Tag = 自己的节点 ID。
     * 从最早位点开始：新节点 ID 的组第一次启动时，Topic 里不会有发给它的旧消息；节点用同一个 ID 重启时沿用已提交的位点，
     * 宕机期间转给它的消息会被补投（到时接收方大概率已不在本节点 → 留待上线补发）
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
                // traceId：恢复发送方的链路 ID，本条消息的所有日志（含下面失败重投的 warn）都带上；处理完还原，消费线程复用不串
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
