package com.merchanthub.order.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Event <-> RocketMQ message. Both send strategies, the consumers, and transaction check-back all use this one codec to keep the format consistent */
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

    public Message toMessage(long eventId, long orderNo, long tenantId, String json) {
        Message msg = new Message(OrderEventTopics.TOPIC, OrderEventTopics.TAG_ORDER_CREATED,
                json.getBytes(StandardCharsets.UTF_8));
        // Index the keys: the dashboard / mqadmin can find this message by order number or event ID, the most common lookup when troubleshooting
        msg.setKeys(orderNo + " " + eventId);
        msg.putUserProperty(OrderEventTopics.PROP_EVENT_ID, String.valueOf(eventId));
        // C1: which merchant the message belongs to; consumers restore the tenant context from it
        return TenantMessages.stamp(msg, tenantId);
    }

    public Message toMessage(OrderCreatedEvent event) {
        return toMessage(event.eventId(), event.orderNo(), event.tenantId(), toJson(event));
    }

    public OrderCreatedEvent fromMessage(Message msg) {
        return fromJson(new String(msg.getBody(), StandardCharsets.UTF_8));
    }
}
