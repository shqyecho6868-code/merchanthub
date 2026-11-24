package com.merchanthub.product.catalog.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.product.catalog.service.ListingStateService;
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
 * 订阅上架审批结果，回写商品状态（D1）。
 * <p>
 * 没有 consumed_event 去重表：{@code UPDATE product ... WHERE status = 1 AND audit_round = ?} 本身就是幂等闸门
 * （同 B3-下 库存确认 / 释放的做法）。重复投递、补偿扫描已经先改过 → CAS 影响 0 行，直接确认消费。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.product.listing-result-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ListingResultConsumerConfig {

    public static final String CONSUMER_GROUP = "product-listing-result";

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer listingResultConsumer(ListingStateService stateService, ObjectMapper objectMapper,
                                                       ObjectProvider<ReconAlarm> alarm,
                                                       @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.subscribe(ListingResultEvent.TOPIC, ListingResultEvent.TAG);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                ListingResultEvent e;
                try {
                    e = objectMapper.readValue(m.getBody(), ListingResultEvent.class);
                } catch (IOException ex) {
                    log.error("无法解析的审批结果消息，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), ex);
                    continue;
                }
                try {
                    // C1：按消息里的商家恢复上下文，CAS 自动带 tenant_id —— 别的商家的消息改不了这个商家的商品
                    TenantMessages.consumeAs(m, () -> stateService.applyResult(e.productId(), e.round(),
                            ListingResultEvent.APPROVED.equals(e.result()), e.rejectReason(), e.processInstanceId(),
                            ListingStateService.SOURCE_MQ));
                } catch (RuntimeException ex) {
                    // 数据库不可用等：事务已回滚，稍后重投；重投仍失败 16 次进死信，补偿扫描反查兜底
                    log.warn("处理审批结果失败，稍后重投 eventId={} productId={} reconsumeTimes={}",
                            e.eventId(), e.productId(), m.getReconsumeTimes(), ex);
                    // E3：最后一次重试也失败 → 告警 + 记 recon_exception
                    DeadLetters.onFailure(alarm.getIfAvailable(), CONSUMER_GROUP, m, consumer.getMaxReconsumeTimes(),
                            TenantMessages.tenantIdOf(m), ex);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
