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
 * traceId 跨服务的两种透传（和 TenantFeignAndMqTest 对应）：Feign 请求头、RocketMQ 消息属性。
 * 一张订单的日志能串起来，靠的就是这里每一跳都把同一个 ID 交给下一跳，且每一跳用完都还原线程。
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
    @DisplayName("Feign：MDC 里的 traceId 写成 X-Trace-Id；模板里原有的值被覆盖")
    void feignCopiesTraceId() {
        RequestTemplate t = new RequestTemplate();
        t.header(TraceContext.HEADER, "stale-trace-0000");
        try (TraceContext.Scope ignored = TraceContext.open(TRACE)) {
            feign.apply(t);
        }
        assertThat(t.headers().get(TraceContext.HEADER)).containsExactly(TRACE);
    }

    @Test
    @DisplayName("Feign：线程上没有 traceId 就不带（下游自己生成）")
    void feignWithoutTrace() {
        RequestTemplate t = new RequestTemplate();
        feign.apply(t);
        assertThat(t.headers()).doesNotContainKey(TraceContext.HEADER);
    }

    @Test
    @DisplayName("MQ 发送：stamp 顺带把当前 traceId 写进消息属性；没有 traceId 就不写；已显式设置的不覆盖")
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
    @DisplayName("MQ 消费：consumeAs 期间 MDC 是消息的 traceId，消费完消费线程上的 MDC 被清掉（线程复用不串链路）")
    void consumeRestoresAndClears() throws Exception {
        MessageExt m = new MessageExt();
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        TenantMessages.stamp(m, 8L);

        ExecutorService consumerThread = Executors.newSingleThreadExecutor();   // 模拟 RocketMQ 复用的消费线程
        try {
            String seenInside = consumerThread.submit(() -> TenantMessages.consumeAs(m, TraceContext::current)).get();
            assertThat(seenInside).isEqualTo(TRACE);
            assertThat(consumerThread.submit(TraceContext::current).get()).as("消费完线程是干净的").isNull();

            // 老消息 / 外部生产者没带 traceId：消费期间新生成一个，本条消息的日志仍能串起来
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
    @DisplayName("MQ 消费：消息缺 tenantId 被拒时 traceId 也被清掉")
    void consumeRejectedStillClears() {
        Message m = new Message("T", "tag", new byte[0]);
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        assertThatThrownBy(() -> TenantMessages.consumeAs(m, () -> { }))
                .isInstanceOf(TenantContextMissingException.class);
        // 断言在 @AfterEach noLeak
    }

    @Test
    @DisplayName("openTrace：不走 consumeAs 的消费者（realtime）用它恢复 / 还原 traceId")
    void openTraceForPlainConsumers() {
        Message m = new Message("T", "tag", new byte[0]);
        m.putUserProperty(TraceContext.MQ_PROPERTY, TRACE);
        try (TraceContext.Scope ignored = TenantMessages.openTrace(m)) {
            assertThat(TraceContext.current()).isEqualTo(TRACE);
        }
    }
}
