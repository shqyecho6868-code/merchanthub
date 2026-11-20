package com.merchanthub.order.mq.consumer;

import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.OrderEventTopics;
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

/**
 * "商家新订单通知"消费者（方案 A：暂放 order 服务，用独立消费组，realtime 任务时迁走）。
 * 两种发送方案（事务消息 / 本地消息表）发出的消息格式一致，这个消费者不需要知道消息是怎么发出来的。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "merchanthub.order.notice-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantNoticeConsumerConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer merchantNoticeConsumer(
            MerchantNoticeService service, OrderEventCodec codec,
            @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
            // 新消费组第一次启动从哪开始读：生产用 LAST（不重放历史）；测试用 FIRST，避免消费者还没分配到队列时漏消息
            @Value("${merchanthub.order.notice-consumer.consume-from:CONSUME_FROM_LAST_OFFSET}") ConsumeFromWhere from)
            throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(MerchantNoticeService.CONSUMER_GROUP);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(from);
        // 只订阅自己关心的事件类型
        consumer.subscribe(OrderEventTopics.TOPIC, OrderEventTopics.TAG_ORDER_CREATED);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                OrderCreatedEvent event;
                try {
                    event = codec.fromMessage(m);
                } catch (IllegalArgumentException e) {
                    // 消息体坏了，重投多少次也解析不了：记 ERROR 后确认掉，避免一直重试（生产接告警 / 死信人工处理）
                    log.error("无法解析的订单事件，丢弃 msgId={} keys={}", m.getMsgId(), m.getKeys(), e);
                    continue;
                }
                try {
                    service.handle(event);
                } catch (RuntimeException e) {
                    // 业务失败（如数据库不可用）：让 RocketMQ 稍后重投。去重记录已随事务回滚，重投时能重新处理
                    log.warn("处理订单事件失败，稍后重投 eventId={} orderNo={} tenantId={} reconsumeTimes={}",
                            event.eventId(), event.orderNo(), event.tenantId(), m.getReconsumeTimes(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
