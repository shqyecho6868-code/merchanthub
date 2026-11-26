package com.merchanthub.common.tenant;

import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * Tenant context: a ThreadLocal holding "who the current thread is acting for" (C1).
 * <p>
 * Written by: the web interceptor (request headers), MQ consumers (message properties), scheduled jobs (explicit {@link #runAsSystem}).
 * Read by: the MyBatis-Plus tenant plugin when building SQL, the Feign interceptor forwarding headers, Redis key prefixes, RBAC checks.
 * <p>
 * <b>Why it must be cleared</b>: Tomcat, RocketMQ consumer and thread pool threads are all reused. If merchant 7's request doesn't clear the thread,
 * the next request on that thread (maybe merchant 8's, maybe one with no identity at all) queries the DB as "merchant 7" --
 * a cross-tenant leak with no error at all. So only the auto-restoring {@link #open} style is offered, never a bare set.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Current identity; null if none */
    public static TenantInfo get() {
        return HOLDER.get();
    }

    /** Current merchant ID; null if there is no context or we're not acting for a merchant */
    public static Long tenantId() {
        TenantInfo info = HOLDER.get();
        return info == null ? null : info.tenantId();
    }

    /** Merchant required: for places only a merchant can act (e.g. tenant Redis keys); throws if missing rather than guessing */
    public static long requireTenantId() {
        Long id = tenantId();
        if (id == null) {
            throw new TenantContextMissingException("No merchant context on the current thread");
        }
        return id;
    }

    /**
     * Switch to info; closing the returned Scope <b>restores the previous state</b> (removes it if there was none).
     * Restore rather than remove: when a system job nests "process as merchant X", it must return to the system identity afterwards, not to "nothing".
     */
    public static Scope open(TenantInfo info) {
        TenantInfo previous = HOLDER.get();
        HOLDER.set(info);
        putMdc(info);
        return new Scope(previous);
    }

    public static void runAs(TenantInfo info, Runnable action) {
        try (Scope ignored = open(info)) {
            action.run();
        }
    }

    public static <T> T callAs(TenantInfo info, Supplier<T> action) {
        try (Scope ignored = open(info)) {
            return action.get();
        }
    }

    /** Run with the cross-tenant system identity: only for entry points that inherently belong to no merchant, like scan jobs and channel callbacks */
    public static void runAsSystem(Runnable action) {
        runAs(TenantInfo.system(), action);
    }

    public static <T> T callAsSystem(Supplier<T> action) {
        return callAs(TenantInfo.system(), action);
    }

    private static void putMdc(TenantInfo info) {
        // Logs carry tenantId (CLAUDE.md section 6): search logs by tenantId to find "whose request" it was
        if (info == null || info.tenantId() == null) {
            MDC.remove("tenantId");
        } else {
            MDC.put("tenantId", String.valueOf(info.tenantId()));
        }
    }

    /** For try-with-resources: restores on close */
    public static final class Scope implements AutoCloseable {
        private final TenantInfo previous;
        private boolean closed;

        private Scope(TenantInfo previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                HOLDER.remove();   // remove rather than set(null): fully deletes the entry from the thread's ThreadLocalMap
            } else {
                HOLDER.set(previous);
            }
            putMdc(previous);
        }
    }
}
