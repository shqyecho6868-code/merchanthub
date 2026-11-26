package com.merchanthub.common.tenant.mq;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.common.trace.TraceContext;
import org.apache.rocketmq.common.message.Message;

import java.util.function.Supplier;

/**
 * Tenant propagation over RocketMQ (C1): messages have no headers, so tenantId goes into a user property on send and is read back on consume to restore the context.
 * <p>
 * Why the sender passes tenantId explicitly instead of reading the ThreadLocal: order's outbox is sent by a scan thread (SYSTEM, cross-tenant),
 * so the thread context isn't the message's merchant; which merchant a message belongs to should come from the message itself (the outbox row, the payment).
 * <p>
 * traceId rides along: {@link #stamp} also copies the current MDC traceId into a property and {@link #consumeAs} restores it to the MDC,
 * so the trace continues from HTTP into MQ without changing a single send / consume site. The difference between traceId and tenantId:
 * traceId comes from the thread context (the trace belongs to whoever triggered it); the outbox scan thread must first open a {@link TraceContext} with the outbox row's trace_id before sending.
 */
public final class TenantMessages {

    private TenantMessages() {
    }

    public static Message stamp(Message msg, long tenantId) {
        msg.putUserProperty(TenantHeaders.MQ_TENANT_ID, String.valueOf(tenantId));
        // Don't overwrite a traceId the caller set explicitly; if the thread has none (e.g. a background thread without a Scope), omit it and the consumer generates a new one
        String traceId = TraceContext.current();
        if (msg.getUserProperty(TraceContext.MQ_PROPERTY) == null && TraceContext.isValid(traceId)) {
            msg.putUserProperty(TraceContext.MQ_PROPERTY, traceId);
        }
        return msg;
    }

    /** traceId from the message; null if missing or malformed */
    public static String traceIdOf(Message msg) {
        String v = msg.getUserProperty(TraceContext.MQ_PROPERTY);
        return TraceContext.isValid(v) ? v : null;
    }

    /**
     * Put the message's traceId into the MDC (generate one if missing) and restore on close. For consumers that don't use {@link #consumeAs} (e.g. realtime push);
     * must be used with try-with-resources: consumer threads are reused, and without restoring, this message's traceId leaks into the next one
     */
    public static TraceContext.Scope openTrace(Message msg) {
        return TraceContext.open(traceIdOf(msg));
    }

    /** Merchant ID from the message; null if missing */
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
     * Run the consume logic as the message's merchant and restore afterwards (RocketMQ consumer threads are reused, so restoring is mandatory).
     * Message without tenantId → throws {@link TenantContextMissingException}: if we don't know which merchant it belongs to, better let it retry into the DLQ than process it across tenants.
     */
    public static <T> T consumeAs(Message msg, Supplier<T> action) {
        // Restore traceId first: if a missing tenantId throws below, this message's traceId is already in the MDC (the caller's logs after catch are outside and unaffected)
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
