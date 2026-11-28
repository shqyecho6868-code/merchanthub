package com.merchanthub.order.job;

import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mq.outbox.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * When does the monitor take over the scan? Only when the stale backlog stops shrinking -- and once it has taken over,
 * it keeps going every round until nothing is stale (one fallback scan is time-boxed, a big backlog needs several).
 */
class OutboxBacklogMonitorTest {

    private OutboxRelay relay;
    private OutboxBacklogMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        relay = mock(OutboxRelay.class);
        monitor = new OutboxBacklogMonitor(mock(OrderOutboxMapper.class), mock(OrdersMapper.class), relay,
                mock(ObjectProvider.class), new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @Test
    void shrinkingBacklog_scheduledScanIsWorking_noFallback() {
        monitor.maybeFallback(900);
        monitor.maybeFallback(600);
        monitor.maybeFallback(300);
        monitor.maybeFallback(0);
        verify(relay, never()).scan();
    }

    @Test
    void flatBacklog_fallsBackAfterTwoRounds() {
        monitor.maybeFallback(500);   // baseline
        monitor.maybeFallback(500);   // 1 round without progress
        verify(relay, never()).scan();
        monitor.maybeFallback(500);   // 2 rounds -> fallback
        verify(relay, times(1)).scan();
    }

    @Test
    void afterFallback_keepsGoingEachRoundUntilNothingIsStale() {
        monitor.maybeFallback(18_000);
        monitor.maybeFallback(18_000);
        monitor.maybeFallback(18_000);   // fallback #1 (time-boxed, drains part of it)
        monitor.maybeFallback(4_600);    // shrank -- but only because of our own fallback: continue right away
        verify(relay, times(2)).scan();
        monitor.maybeFallback(0);        // done: stop
        monitor.maybeFallback(0);
        verify(relay, times(2)).scan();
    }

    @Test
    void schedulerBackAfterFallback_noMoreFallbackOnceDrained() {
        monitor.maybeFallback(100);
        monitor.maybeFallback(100);
        monitor.maybeFallback(100);   // fallback
        monitor.maybeFallback(0);     // drained (by fallback or the restored scheduler)
        monitor.maybeFallback(50);    // new stale rows appear: baseline again, no immediate takeover
        verify(relay, times(1)).scan();
    }
}
