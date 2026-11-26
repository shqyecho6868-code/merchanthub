package com.merchanthub.common.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Writes the current traceId into X-Trace-Id on Feign calls; the downstream {@link TraceIdFilter} reads it and keeps using the same ID.
 * E.g. when an order is placed, order calls product-inventory to reserve stock, and both sides log the same traceId.
 * <p>
 * Like TenantFeignInterceptor, it relies on interceptors running on the caller's thread to read the MDC (Sentinel's semaphore isolation doesn't switch threads).
 */
public class TraceFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        template.removeHeader(TraceContext.HEADER);   // avoid an existing template value appearing alongside the current one
        String traceId = TraceContext.current();
        if (TraceContext.isValid(traceId)) {
            template.header(TraceContext.HEADER, traceId);
        }
        // No traceId (e.g. a background thread without a Scope): omit it; downstream generates its own, the trace breaks but nothing fails
    }
}
