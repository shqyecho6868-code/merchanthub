package com.merchanthub.payment.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class PaymentEventCodec {

    private final ObjectMapper objectMapper;

    public Message toMessage(PaySuccessEvent event, String attempt) {
        try {
            Message msg = new Message(PaymentEventTopics.TOPIC, PaymentEventTopics.TAG_PAY_SUCCESS,
                    objectMapper.writeValueAsBytes(event));
            // keys 建索引：按订单号 / 支付单号都能在控制台查到这条消息
            msg.setKeys(event.orderNo() + " " + event.payNo());
            msg.putUserProperty(PaymentEventTopics.PROP_EVENT_ID, String.valueOf(event.eventId()));
            msg.putUserProperty(PaymentEventTopics.PROP_ATTEMPT, attempt);
            return msg;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public PaySuccessEvent fromMessage(Message msg) {
        try {
            return objectMapper.readValue(new String(msg.getBody(), StandardCharsets.UTF_8), PaySuccessEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法解析 PaySuccessEvent", e);
        }
    }
}
