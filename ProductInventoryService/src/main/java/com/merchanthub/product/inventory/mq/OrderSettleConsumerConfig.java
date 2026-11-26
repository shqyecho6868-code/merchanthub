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
 * Subscribes to order "paid / closed" events: paid → confirm deduction; closed → release the reservation.
 * <p>
 * There is <b>no</b> consumed_event dedup table here: the reservation record's {@code WHERE status = 10} CAS is itself the idempotency gate;
 * on redelivery the CAS affects 0 rows and stock isn't touched again.
 * ORDER_PAID and ORDER_CLOSED never both exist for the same order (order-side CAS lets only one win), so concurrent or out-of-order consumption doesn't affect the result.
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
                                                     // Max retries, -1 = RocketMQ default of 16; the DLQ test sets it to 0 (straight to DLQ on first failure)
                                                     @Value("${merchanthub.inventory.settle-consumer.max-reconsume-times:-1}") int maxReconsume)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setMaxReconsumeTimes(maxReconsume);
        consumer.subscribe(OrderSettledEvent.TOPIC, OrderSettledEvent.TAG_ORDER_PAID + " || " + OrderSettledEvent.TAG_ORDER_CLOSED);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                // traceId: restore the sender's trace ID so every log for this message (including the retry warn below) carries it; restored afterwards so pooled consumer threads don't leak it
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    OrderSettledEvent event;
                    try {
                        event = objectMapper.readValue(m.getBody(), OrderSettledEvent.class);
                    } catch (IOException e) {
                        log.error("Unparsable order event, discarding msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                        continue;
                    }
                    try {
                        // Restore the tenant context from the merchant in the message; reservation and inventory SQL automatically carry tenant_id
                        TenantMessages.consumeAs(m, () -> {
                            if (OrderSettledEvent.TAG_ORDER_PAID.equals(m.getTags())) {
                                settleService.confirm(event.orderNo(), event.tenantId());
                            } else {
                                settleService.release(event.orderNo(), event.tenantId(), event.event());
                            }
                        });
                    } catch (RuntimeException e) {
                        // Database unavailable / reserved insufficient (data inconsistent): the whole transaction is rolled back, let RocketMQ redeliver later
                        log.warn("Failed to process order event, will redeliver tag={} eventId={} orderNo={} reconsumeTimes={}",
                                m.getTags(), event.eventId(), event.orderNo(), m.getReconsumeTimes(), e);
                        // Alert before retries are exhausted and it goes to the DLQ; reservation recon (reservationReconJob) backstops it based on the order's real status
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
