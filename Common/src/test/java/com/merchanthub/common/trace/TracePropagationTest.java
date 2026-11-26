package com.merchanthub.common.trace;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.mq.TenantMessages;
import feign.RequestTemplate;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two ways traceId crosses services (mirrors TenantFeignAndMqTest): Feign request headers and RocketMQ message properties.
 * An order's logs link up only because every hop hands the same ID to the next, and every hop restores its thread afterwards.
 */
class TracePropagationTest {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    private final TraceFeignInterceptor feign = new TraceFeignInterceptor();

    @AfterEach
    void noLeak() {
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Feign: the MDC traceId is written as X-Trace-Id; an existing template value is overwritten")
    void feignCopiesTraceId() {
        RequestTemplate t = new RequestTemplate();
        t.header(TraceContext.HEADER, "stale-trace-0000");
        try (TraceContext.Scope ignored = TraceContext.open(TRACE)) {
            feign.apply(t);
        }
        assertThat(t.headers().get(TraceContext.HEADER)).containsExactly(TRACE);
    }

    @Test
    @DisplayName("Feign: no traceId on the thread → header omitted (downstream generates its own)")
    void feignWithoutTrace() {
        RequestTemplate t = new RequestTemplate();
        feign.apply(t);
        assertThat(t.headers()).doesNotContainKey(TraceContext.HEADER);
    }

    @Test
    @DisplayName("MQ send: stamp also writes the current traceId into a message property; nothing if there is no traceId; an explicitly set one isn't overwritten")
    void stampCarriesTraceId() {
        Message withTrace = new Message("T", "tag", new byte[0]);
        try (TraceContext.Scope ignored = TraceContext.open(TRACE)) {
            TenantMessages.stamp(withTrace, 7L);
        }
        assertThat(withTrace.getUserProperty(TraceContext.MQ_PROPERTY)).isEqualTo(TRACE);
        assertThat(TenantMessages.traceIdOf(withTrace)).isEqualTo(TRACE);

        Message noTrace = TenantMessages.stamp(new Message("T", "tag", new byte[0]), 7L);
        assertThat(noTrace.getUserProperty(TraceContext.MQ_PROPERTY)).isNull();

        Message preset = new Message("T", "tag", new byte[0]);
        preset.putUserProperty(TraceContext.MQ_PROPERTY, "preset-trace-01");
        try (TraceContext.Scope ignored = TraceContext.open(TRACE)) {
            TenantMessages.stamp(preset, 7L);
        }
        assertThat(preset.getUserProperty(TraceContext.MQ_PROPERTY)).isEqualTo("preset-trace-01");
    }

    @Test
    @DisplayName("MQ consume: during consumeAs the MDC holds the message's traceId; afterwards the consumer thread's MDC is cleared (no trace leak on thread reuse)")
    void consumeRestoresAndClears() throws Exception {
        MessageExt m = new MessageExt();
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        TenantMessages.stamp(m, 8L);

        ExecutorService consumerThread = Executors.newSingleThreadExecutor();   // simulates RocketMQ's reused consumer thread
        try {
            String seenInside = consumerThread.submit(() -> TenantMessages.consumeAs(m, TraceContext::current)).get();
            assertThat(seenInside).isEqualTo(TRACE);
            assertThat(consumerThread.submit(TraceContext::current).get()).as("The thread is clean after consuming").isNull();

            // Old messages / external producers without a traceId: a new one is generated for the duration of consumption, so this message's logs still link up
            MessageExt old = new MessageExt();
            TenantMessages.stamp(old, 8L);
            String generated = consumerThread.submit(() -> TenantMessages.consumeAs(old, TraceContext::current)).get();
            assertThat(generated).matches("[0-9a-f]{32}");
            assertThat(consumerThread.submit(TraceContext::current).get()).isNull();
        } finally {
            consumerThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("MQ consume: traceId is also cleared when a message without tenantId is rejected")
    void consumeRejectedStillClears() {
        Message m = new Message("T", "tag", new byte[0]);
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        assertThatThrownBy(() -> TenantMessages.consumeAs(m, () -> { }))
                .isInstanceOf(TenantContextMissingException.class);
        // Assertion lives in @AfterEach noLeak
    }

    @Test
    @DisplayName("openTrace: consumers that don't use consumeAs (realtime) restore / reset the traceId with it")
    void openTraceForPlainConsumers() {
        Message m = new Message("T", "tag", new byte[0]);
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        try (TraceContext.Scope ignored = TenantMessages.openTrace(m)) {
            assertThat(TraceContext.current()).isEqualTo(TRACE);
        }
    }
}
