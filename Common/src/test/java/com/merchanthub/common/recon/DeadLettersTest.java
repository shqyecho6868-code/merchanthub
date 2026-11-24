package com.merchanthub.common.recon;

import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLettersTest {

    private static MessageExt msg(int reconsumeTimes) {
        MessageExt m = new MessageExt();
        m.setTopic("ORDER_EVENT");
        m.setTags("ORDER_PAID");
        m.setReconsumeTimes(reconsumeTimes);
        return m;
    }

    @Test
    void lastAttemptUsesDefault16WhenMaxIsMinusOne() {
        assertThat(DeadLetters.isLastAttempt(msg(15), -1)).isFalse();
        assertThat(DeadLetters.isLastAttempt(msg(16), -1)).isTrue();
        assertThat(DeadLetters.isLastAttempt(msg(0), 0)).isTrue();
        assertThat(DeadLetters.isLastAttempt(msg(1), 3)).isFalse();
    }

    @Test
    void raisesAlarmOnlyOnLastAttempt() {
        ReconAlarm alarm = mock(ReconAlarm.class);
        assertThat(DeadLetters.onFailure(alarm, "g", msg(1), 3, 7L, new RuntimeException("x"))).isFalse();
        verify(alarm, never()).raise(any(Long.class), anyString(), anyString(), anyString());

        assertThat(DeadLetters.onFailure(alarm, "g", msg(3), 3, 7L, new RuntimeException("x"))).isTrue();
        verify(alarm).raise(eq(7L), eq(ReconAlarm.DEAD_LETTER), anyString(), anyString());
    }

    @Test
    void alarmFailureDoesNotPropagate() {
        ReconAlarm alarm = mock(ReconAlarm.class);
        when(alarm.raise(any(Long.class), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("db down"));
        assertThat(DeadLetters.onFailure(alarm, "g", msg(0), 0, null, null)).isTrue();
        assertThat(DeadLetters.onFailure(null, "g", msg(0), 0, null, null)).isTrue();
    }

    @Test
    void reconAlarmIsIdempotentByInsertIgnore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        ReconAlarm alarm = new ReconAlarm(jdbc);
        assertThat(alarm.raise(7L, ReconAlarm.OUTBOX_FAILED, "k", "d")).isTrue();
        assertThat(alarm.raise(7L, ReconAlarm.OUTBOX_FAILED, "k", "x".repeat(600))).isFalse();
    }
}
