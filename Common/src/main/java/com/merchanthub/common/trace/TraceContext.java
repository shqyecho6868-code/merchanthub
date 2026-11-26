package com.merchanthub.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trace traceId: a request enters at the gateway and flows HTTP header → MDC → Feign header → MQ message property → consumer MDC,
 * carrying the same ID end to end, so a single traceId search pulls up every service "one order" passed through.
 * <p>
 * Why hand-rolled instead of Micrometer Tracing / OTel: this project only needs "logs that link up", not span trees or sampled reporting;
 * hand-rolled is one MDC key + three propagation points, no new dependencies, and easy to understand at a glance. Trade-off: no span timings, no call topology.
 * <p>
 * Like {@code TenantContext}, only the restore-after-use {@link #open} style is offered: threads (Tomcat, MQ consumers, thread pools) are reused,
 * and without restoring, the next task would log under the previous request's traceId, stitching two unrelated traces together during troubleshooting.
 */
public final class TraceContext {

    /** HTTP header used by the gateway / between services */
    public static final String HEADER = "X-Trace-Id";
    /** Log MDC key, printed via %X{traceId} in the log pattern */
    public static final String MDC_KEY = "traceId";
    /** User property name on RocketMQ messages (messages have no headers, so like tenantId it goes in a property) */
    public static final String MQ_PROPERTY = "traceId";

    /**
     * An incoming traceId is only accepted in this format: it can align with upstream (e.g. Nginx / clients) while keeping arbitrary strings (newlines, huge values)
     * out of the logs -- this step blocks log injection and oversized log lines. Invalid values are treated as absent and a new one is generated
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private TraceContext() {
    }

    /** traceId of the current thread; null if none */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static boolean isValid(String traceId) {
        return traceId != null && VALID.matcher(traceId).matches();
    }

    /** 32 hex characters (UUID without dashes), the same length as the W3C traceparent trace-id */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Reuse it if valid, otherwise generate a new one */
    public static String reuseOrNew(String candidate) {
        return isValid(candidate) ? candidate : newId();
    }

    /**
     * Put the traceId (or a new one if invalid / empty) into the MDC; closing the returned Scope restores the previous value (removes it if there was none).
     * Restore rather than remove: e.g. if an MQ consumer nests another block with its own traceId, the outer traceId must come back afterwards
     */
    public static Scope open(String traceId) {
        String previous = MDC.get(MDC_KEY);
        String id = reuseOrNew(traceId);
        MDC.put(MDC_KEY, id);
        return new Scope(previous, id);
    }

    /** For try-with-resources: restores on close */
    public static final class Scope implements AutoCloseable {
        private final String previous;
        private final String traceId;
        private boolean closed;

        private Scope(String previous, String traceId) {
            this.previous = previous;
            this.traceId = traceId;
        }

        /** The traceId in effect for this scope */
        public String traceId() {
            return traceId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
