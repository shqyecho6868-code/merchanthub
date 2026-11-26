package com.merchanthub.order.mq.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.common.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.merchanthub.common.recon.DeadLetters;
import com.merchanthub.common.recon.ReconAlarm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 订阅 payment 的"支付成功"事件。E3：最后一次重试仍失败时（即将进死信）告警并记 recon_exception */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.order.pay-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PaySuccessConsumerConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer paySuccessConsumer(
            OrderPaidService service, ObjectMapper objectMapper, ObjectProvider<ReconAlarm> alarm,
            @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
            @Value("${merchanthub.order.pay-consumer.consume-from:CONSUME_FROM_LAST_OFFSET}") ConsumeFromWhere from)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(OrderPaidService.CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(from);
        consumer.subscribe(PaySuccessEvent.TOPIC, PaySuccessEvent.TAG);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                // traceId：恢复发送方的链路 ID，本条消息的所有日志（含下面失败重投的 warn）都带上；处理完还原，消费线程复用不串
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    PaySuccessEvent event;
                    try {
                        event = objectMapper.readValue(m.getBody(), PaySuccessEvent.class);
                    } catch (IOException e) {
                        log.error("无法解析的支付成功事件，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                        continue;
                    }
                    try {
                        // C1：按消息里的商家恢复租户上下文再处理，处理完还原（消费线程是复用的）
                        TenantMessages.consumeAs(m, () -> service.handle(event));
                    } catch (OrderNotVisibleException e) {
                        log.warn("订单尚不可见，稍后重投 eventId={} orderNo={} reconsumeTimes={}",
                                event.eventId(), event.orderNo(), m.getReconsumeTimes());
                        // 订单始终不出现（重试 16 次约 4 小时 46 分）：进死信前告警，交给对账
                        DeadLetters.onFailure(alarm.getIfAvailable(), OrderPaidService.CONSUMER_GROUP, m,
                                consumer.getMaxReconsumeTimes(), event.tenantId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    } catch (RuntimeException e) {
                        // 数据库不可用等：整个事务（含去重记录）已回滚，让 RocketMQ 稍后重投
                        log.warn("处理支付成功事件失败，稍后重投 eventId={} orderNo={} reconsumeTimes={}",
                                event.eventId(), event.orderNo(), m.getReconsumeTimes(), e);
                        DeadLetters.onFailure(alarm.getIfAvailable(), OrderPaidService.CONSUMER_GROUP, m,
                                consumer.getMaxReconsumeTimes(), event.tenantId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
