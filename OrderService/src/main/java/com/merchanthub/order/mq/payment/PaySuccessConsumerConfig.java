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

/** Subscribes to payment's "payment succeeded" event. E3: when the last retry still fails (about to go to the DLQ), alert and record recon_exception */
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
                // traceId: restore the sender's trace ID so every log for this message (including the redelivery warn below) carries it; restored afterwards since consumer threads are reused
                try (TraceContext.Scope ignoredTrace = TenantMessages.openTrace(m)) {
                    PaySuccessEvent event;
                    try {
                        event = objectMapper.readValue(m.getBody(), PaySuccessEvent.class);
                    } catch (IOException e) {
                        log.error("Unparseable payment-success event, discarding msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                        continue;
                    }
                    try {
                        // C1: restore the tenant context from the merchant in the message before processing, and restore afterwards (consumer threads are reused)
                        TenantMessages.consumeAs(m, () -> service.handle(event));
                    } catch (OrderNotVisibleException e) {
                        log.warn("Order not visible yet, will be redelivered eventId={} orderNo={} reconsumeTimes={}",
                                event.eventId(), event.orderNo(), m.getReconsumeTimes());
                        // Order never appears (16 retries, about 4h46m): alert before it goes to the DLQ and hand it to reconciliation
                        DeadLetters.onFailure(alarm.getIfAvailable(), OrderPaidService.CONSUMER_GROUP, m,
                                consumer.getMaxReconsumeTimes(), event.tenantId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    } catch (RuntimeException e) {
                        // DB unavailable etc.: the whole transaction (including the dedup record) has rolled back; let RocketMQ redeliver later
                        log.warn("Failed to process payment-success event, will be redelivered eventId={} orderNo={} reconsumeTimes={}",
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
