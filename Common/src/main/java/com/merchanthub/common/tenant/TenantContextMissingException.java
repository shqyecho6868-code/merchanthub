package com.merchanthub.common.tenant;

/**
 * No tenant context available when accessing a business table (fail-closed, C1 design Q3).
 * It means some entry point forgot to set the context (a new MQ consumer, scheduled job, thread pool...), which is a code bug:
 * better to fail than to query the whole table without tenant_id.
 */
public class TenantContextMissingException extends IllegalStateException {

    public TenantContextMissingException(String message) {
        super(message);
    }
}
