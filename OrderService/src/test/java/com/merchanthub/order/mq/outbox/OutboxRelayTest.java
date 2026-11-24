package com.merchanthub.order.mq.outbox;

import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
    private final OutboxRelay relay = new OutboxRelay(mapper, producer, mock(ExecutorService.class), clock);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "leaseSeconds", 30L);
        ReflectionTestUtils.setField(relay, "baseBackoffSeconds", 10L);
        ReflectionTestUtils.setField(relay, "maxBackoffSeconds", 600L);
        ReflectionTestUtils.setField(relay, "maxRetries", 3);
    }

    private static OrderOutbox row(int retryCount) {
        OrderOutbox r = new OrderOutbox();
        r.setId(1L);
        r.setTenantId(7L);
        r.setEventId(555L);
        r.setBizKey("9001");
        r.setTopic("ORDER_EVENT");
        r.setTag("ORDER_CREATED");
        r.setPayload("{\"eventId\":555}");
        r.setStatus(OrderOutbox.PENDING);
        r.setRetryCount(retryCount);
        return r;
    }

    private static SendResult ok() {
        SendResult r = new SendResult();
        r.setSendStatus(SendStatus.SEND_OK);
        return r;
    }

    @Test
    void claimLost_doesNotSend() throws Exception {
        when(mapper.claim(anyLong(), any(), any())).thenReturn(0);
        relay.publish(row(0));
        verify(producer, never()).send(any(Message.class));
    }

    @Test
    void sendOk_marksSent_withOurEventIdAndKeys() throws Exception {
        when(mapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(producer.send(any(Message.class))).thenReturn(ok());
        relay.publish(row(0));

        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(msg.capture());
        assertThat(msg.getValue().getKeys()).isEqualTo("9001 555");
        assertThat(msg.getValue().getUserProperty("EVENT_ID")).isEqualTo("555");
        verify(mapper).markSent(eq(1L), any());
    }

    @Test
    void sendFails_recordsRetryWithBackoff() throws Exception {
        when(mapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("broker down"));
        relay.publish(row(0));
        verify(mapper).markRetry(eq(1L), eq(0), eq(1), eq(OrderOutbox.PENDING), any(), any());
        verify(mapper, never()).markSent(anyLong(), any());
    }

    @Test
    void sendFails_atMaxRetries_marksFailed() throws Exception {
        when(mapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("broker down"));
        relay.publish(row(2));
        verify(mapper).markRetry(eq(1L), eq(2), eq(3), eq(OrderOutbox.FAILED), any(), any());
    }

    @Test
    void scan_publishesEachDueRow() throws Exception {
        OrderOutbox a = row(0);
        OrderOutbox b = row(0);
        b.setId(2L);
        when(mapper.selectDue(any(), eq(100))).thenReturn(List.of(a, b));
        when(mapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(producer.send(any(Message.class))).thenReturn(ok());
        relay.scan();
        verify(mapper).markSent(eq(1L), any());
        verify(mapper).markSent(eq(2L), any());
    }

    @Test
    void backoff_doublesAndCaps() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(relay.backoff(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(relay.backoff(20)).isEqualTo(Duration.ofSeconds(600));
    }
}
