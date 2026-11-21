package com.merchanthub.order.mq.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.order.mq.OrderEventTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 订阅 ORDER_TIMEOUT 定时消息。结构同 PaySuccessConsumerConfig */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.order.timeout-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PayTimeoutConsumerConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer payTimeoutConsumer(PayTimeoutService service, ObjectMapper objectMapper,
                                                    @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(PayTimeoutService.CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.subscribe(OrderEventTopics.TIMEOUT_TOPIC, OrderEventTopics.TAG_PAY_TIMEOUT);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                PayTimeoutMessage msg;
                try {
                    msg = objectMapper.readValue(m.getBody(), PayTimeoutMessage.class);
                } catch (IOException e) {
                    log.error("无法解析的超时关单消息，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                    continue;
                }
                try {
                    service.handle(msg);
                } catch (RuntimeException e) {
                    log.warn("处理超时关单消息失败，稍后重投 eventId={} orderNo={} reconsumeTimes={}",
                            msg.eventId(), msg.orderNo(), m.getReconsumeTimes(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
