package com.merchanthub.product.inventory.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.common.trace.TraceContext;
import com.merchanthub.product.inventory.service.ReservationSettleService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.ObjectProvider;
import com.merchanthub.common.recon.DeadLetters;
import com.merchanthub.common.recon.ReconAlarm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 订阅订单的"已支付 / 已关闭"事件：已支付 → 确认扣减；已关闭 → 释放预占。
 * <p>
 * 这里<b>没有</b> consumed_event 去重表：预占记录 {@code WHERE status = 10} 的 CAS 本身就是幂等闸门，
 * 重复投递时 CAS 影响 0 行，库存不会再动（B3-下笔记第 2 节）。
 * 同一订单的 ORDER_PAID 和 ORDER_CLOSED 不会同时存在（order 侧 CAS 只有一方赢），所以并发消费、乱序都不影响结果。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.inventory.settle-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class OrderSettleConsumerConfig {

    public static final String CONSUMER_GROUP = "inventory-order-settle";

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer orderSettleConsumer(ReservationSettleService settleService, ObjectMapper objectMapper,
                                                     ObjectProvider<ReconAlarm> alarm,
                                                     @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
                                                     // 最大重试次数，-1 = RocketMQ 默认 16 次；死信测试调成 0（第一次失败就进死信）
                                                     @Value("${merchanthub.inventory.settle-consumer.max-reconsume-times:-1}") int maxReconsume)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setMaxReconsumeTimes(maxReconsume);
        consumer.subscribe(OrderSettledEvent.TOPIC, OrderSettledEvent.TAG_ORDER_PAID + " || " + OrderSettledEvent.TAG_ORDER_CLOSED);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                // traceId：恢复发送方的链路 ID，本条消息的所有日志（含下面失败重投的 warn）都带上；处理完还原，消费线程复用不串
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    OrderSettledEvent event;
                    try {
                        event = objectMapper.readValue(m.getBody(), OrderSettledEvent.class);
                    } catch (IOException e) {
                        log.error("无法解析的订单事件，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                        continue;
                    }
                    try {
                        // C1：按消息里的商家恢复租户上下文，预占记录、库存的 SQL 都会自动带上 tenant_id
                        TenantMessages.consumeAs(m, () -> {
                            if (OrderSettledEvent.TAG_ORDER_PAID.equals(m.getTags())) {
                                settleService.confirm(event.orderNo(), event.tenantId());
                            } else {
                                settleService.release(event.orderNo(), event.tenantId(), event.event());
                            }
                        });
                    } catch (RuntimeException e) {
                        // 数据库不可用 / reserved 不足（数据不一致）：事务已整体回滚，让 RocketMQ 稍后重投
                        log.warn("处理订单事件失败，稍后重投 tag={} eventId={} orderNo={} reconsumeTimes={}",
                                m.getTags(), event.eventId(), event.orderNo(), m.getReconsumeTimes(), e);
                        // E3：重试耗尽进死信前告警；预占对账（reservationReconJob）会按订单真实状态兜底了结
                        DeadLetters.onFailure(alarm.getIfAvailable(), CONSUMER_GROUP, m, consumer.getMaxReconsumeTimes(),
                                event.tenantId(), e);
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
