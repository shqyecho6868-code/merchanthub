package com.merchanthub.product.catalog.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.common.trace.TraceContext;
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
 * Subscribes to listing approval results and writes back the product status (D1).
 * <p>
 * No consumed_event dedup table: {@code UPDATE product ... WHERE status = 1 AND audit_round = ?} is itself the idempotency gate
 * (same as B3-下's inventory confirm / release). Redelivery, or the compensation scan having already applied it → the CAS affects 0 rows and we just ack.
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
                // traceId: restore the sender's trace ID so every log for this message (including the retry warn below) carries it; restored afterwards so pooled consumer threads don't leak it
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    ListingResultEvent e;
                    try {
                        e = objectMapper.readValue(m.getBody(), ListingResultEvent.class);
                    } catch (IOException ex) {
                        log.error("Unparsable approval result message, discarding msgId={} keys={}", m.getMsgId(), m.getKeys(), ex);
                        continue;
                    }
                    try {
                        // C1: restore the context from the merchant in the message; the CAS automatically carries tenant_id — another merchant's message can't modify this merchant's products
                        TenantMessages.consumeAs(m, () -> stateService.applyResult(e.productId(), e.round(),
                                ListingResultEvent.APPROVED.equals(e.result()), e.rejectReason(), e.processInstanceId(),
                                ListingStateService.SOURCE_MQ));
                    } catch (RuntimeException ex) {
                        // Database unavailable etc.: the transaction is rolled back, redeliver later; after 16 failed retries it goes to the DLQ and the compensation scan backstops it
                        log.warn("Failed to process approval result, will redeliver eventId={} productId={} reconsumeTimes={}",
                                e.eventId(), e.productId(), m.getReconsumeTimes(), ex);
                        // E3: the last retry also failed → alert + record recon_exception
                        DeadLetters.onFailure(alarm.getIfAvailable(), CONSUMER_GROUP, m, consumer.getMaxReconsumeTimes(),
                                TenantMessages.tenantIdOf(m), ex);
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
