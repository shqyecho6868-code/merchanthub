package com.merchanthub.order.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 事件 ⇄ RocketMQ 消息。两种发送方案、消费者、事务回查都用这一套，保证格式一致 */
@Component
@RequiredArgsConstructor
public class OrderEventCodec {

    private final ObjectMapper objectMapper;

    public String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public OrderCreatedEvent fromJson(String json) {
        try {
            return objectMapper.readValue(json, OrderCreatedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法解析 OrderCreatedEvent: " + json, e);
        }
    }

    public Message toMessage(long eventId, long orderNo, String json) {
        Message msg = new Message(OrderEventTopics.TOPIC, OrderEventTopics.TAG_ORDER_CREATED,
                json.getBytes(StandardCharsets.UTF_8));
        // keys 建索引：控制台 / mqadmin 能按订单号或事件 ID 查到这条消息，排查问题时最常用
        msg.setKeys(orderNo + " " + eventId);
        msg.putUserProperty(OrderEventTopics.PROP_EVENT_ID, String.valueOf(eventId));
        return msg;
    }

    public Message toMessage(OrderCreatedEvent event) {
        return toMessage(event.eventId(), event.orderNo(), toJson(event));
    }

    public OrderCreatedEvent fromMessage(Message msg) {
        return fromJson(new String(msg.getBody(), StandardCharsets.UTF_8));
    }
}
