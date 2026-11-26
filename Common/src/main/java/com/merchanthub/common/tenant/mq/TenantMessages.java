package com.merchanthub.common.tenant.mq;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.common.trace.TraceContext;
import org.apache.rocketmq.common.message.Message;

import java.util.function.Supplier;

/**
 * RocketMQ 的租户透传（C1）：消息没有请求头，发送时把 tenantId 写进 user property，消费时读出来恢复上下文。
 * <p>
 * 为什么发送方显式传 tenantId、不从 ThreadLocal 取：order 的发件箱是扫描线程（SYSTEM，跨租户）在发，
 * 线程上下文不是这条消息的商家；消息属于哪个商家应该由消息自己（outbox 行、支付单）决定。
 * <p>
 * traceId 搭同一趟车：{@link #stamp} 顺手把当前 MDC 的 traceId 写进属性，{@link #consumeAs} 顺手恢复到 MDC，
 * 所有发送 / 消费点不用改一行就能把链路从 HTTP 延续到 MQ。traceId 和 tenantId 的区别：
 * traceId 取线程上下文（谁触发的就算谁的链路），发件箱扫描线程要先按 outbox 行的 trace_id 打开 {@link TraceContext} 再发。
 */
public final class TenantMessages {

    private TenantMessages() {
    }

    public static Message stamp(Message msg, long tenantId) {
        msg.putUserProperty(TenantHeaders.MQ_TENANT_ID, String.valueOf(tenantId));
        // 调用方已经显式设过 traceId 就不覆盖；线程上没有（如没包 Scope 的后台线程）就不带，消费端会新生成一个
        String traceId = TraceContext.current();
        if (msg.getUserProperty(TraceContext.MQ_PROPERTY) == null && TraceContext.isValid(traceId)) {
            msg.putUserProperty(TraceContext.MQ_PROPERTY, traceId);
        }
        return msg;
    }

    /** 消息里的 traceId；没有或格式不对返回 null */
    public static String traceIdOf(Message msg) {
        String v = msg.getUserProperty(TraceContext.MQ_PROPERTY);
        return TraceContext.isValid(v) ? v : null;
    }

    /**
     * 把消息的 traceId 放进 MDC（没有就新生成），关闭时还原。给不走 {@link #consumeAs} 的消费者用（如 realtime 推送），
     * 必须 try-with-resources：消费线程复用，不还原就把这条消息的 traceId 带到下一条
     */
    public static TraceContext.Scope openTrace(Message msg) {
        return TraceContext.open(traceIdOf(msg));
    }

    /** 消息里的商家 ID；没有返回 null */
    public static Long tenantIdOf(Message msg) {
        String v = msg.getUserProperty(TenantHeaders.MQ_TENANT_ID);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 以消息所属商家的身份执行消费逻辑，执行完还原（RocketMQ 消费线程是复用的，必须还原）。
     * 消息没带 tenantId → 抛 {@link TenantContextMissingException}：不知道是哪个商家的消息，宁可重投到死信，也不能跨租户处理。
     */
    public static <T> T consumeAs(Message msg, Supplier<T> action) {
        // traceId 先恢复：后面缺 tenantId 抛异常时，这条消息的 traceId 已经在 MDC 里（调用方 catch 后的日志在外面，不受影响）
        try (TraceContext.Scope ignored = openTrace(msg)) {
            Long tenantId = tenantIdOf(msg);
            if (tenantId == null) {
                throw new TenantContextMissingException("消息缺少 " + TenantHeaders.MQ_TENANT_ID + " 属性 keys=" + msg.getKeys());
            }
            return TenantContext.callAs(TenantInfo.systemOf(tenantId), action);
        }
    }

    public static void consumeAs(Message msg, Runnable action) {
        consumeAs(msg, () -> {
            action.run();
            return null;
        });
    }
}
