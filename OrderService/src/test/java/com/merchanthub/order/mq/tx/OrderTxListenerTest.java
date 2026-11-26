package com.merchanthub.order.mq.tx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderTxListenerTest {

    private final OrdersMapper mapper = mock(OrdersMapper.class);
    private final OrderEventCodec codec = new OrderEventCodec(
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
    private final OrderTxListener listener = new OrderTxListener(mapper, codec, clock);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "rollbackMinAgeSeconds", 30L);
    }

    /** By default the message was born 60 seconds ago */
    private MessageExt checkMsg() {
        return checkMsg(60_000);
    }

    private MessageExt checkMsg(long ageMs) {
        Message m = codec.toMessage(new OrderCreatedEvent(1L, 9001L, 7L, 1001L, 9900L, LocalDateTime.now()));
        MessageExt ext = new MessageExt();
        ext.setBody(m.getBody());
        ext.setKeys(m.getKeys());
        ext.setBornTimestamp(clock.millis() - ageMs);
        return ext;
    }

    @Test
    void execute_localTxOk_commits() {
        LocalTx tx = new LocalTx(() -> { });
        assertThat(listener.executeLocalTransaction(new Message(), tx)).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(tx.executed()).isTrue();
        assertThat(tx.failure()).isNull();
    }

    @Test
    void execute_localTxFails_rollsBack_andKeepsCause() {
        IllegalStateException boom = new IllegalStateException("boom");
        LocalTx tx = new LocalTx(() -> {
            throw boom;
        });
        assertThat(listener.executeLocalTransaction(new Message(), tx)).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThat(tx.failure()).isSameAs(boom);
    }

    @Test
    void check_orderExists_commits() {
        when(mapper.exists(any())).thenReturn(true);
        assertThat(listener.checkLocalTransaction(checkMsg())).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
    }

    @Test
    void check_orderMissing_rollsBack() {
        when(mapper.exists(any())).thenReturn(false);
        assertThat(listener.checkLocalTransaction(checkMsg())).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    /** Order not found yet but the message is still fresh: the local transaction may not be done, so don't roll back */
    @Test
    void check_orderMissing_butMessageYoung_unknown() {
        when(mapper.exists(any())).thenReturn(false);
        assertThat(listener.checkLocalTransaction(checkMsg(5_000))).isEqualTo(LocalTransactionState.UNKNOW);
    }

    /** A DB query failure must not be treated as "order doesn't exist" and rolled back, or we might delete the message of an order that already exists */
    @Test
    void check_dbError_unknown_letBrokerRetry() {
        when(mapper.exists(any())).thenThrow(new QueryTimeoutException("db down"));
        assertThat(listener.checkLocalTransaction(checkMsg())).isEqualTo(LocalTransactionState.UNKNOW);
    }

    @Test
    void check_corruptBody_rollsBack() {
        MessageExt ext = new MessageExt();
        ext.setBody("not json".getBytes(StandardCharsets.UTF_8));
        assertThat(listener.checkLocalTransaction(ext)).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }
}
