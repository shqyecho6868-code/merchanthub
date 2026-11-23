package com.merchanthub.order.mq.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
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

/** 订阅 payment 的"支付成功"事件。结构同 MerchantNoticeConsumerConfig */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.order.pay-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PaySuccessConsumerConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer paySuccessConsumer(
            OrderPaidService service, ObjectMapper objectMapper,
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
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } catch (RuntimeException e) {
                    // 数据库不可用等：整个事务（含去重记录）已回滚，让 RocketMQ 稍后重投
                    log.warn("处理支付成功事件失败，稍后重投 eventId={} orderNo={} reconsumeTimes={}",
                            event.eventId(), event.orderNo(), m.getReconsumeTimes(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
