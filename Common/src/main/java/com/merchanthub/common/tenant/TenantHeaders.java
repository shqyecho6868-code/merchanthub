package com.merchanthub.common.tenant;

import java.util.List;

/**
 * Identity headers injected by the gateway after parsing the JWT and forwarded between services via Feign (C1).
 * <p>
 * Downstream services <b>trust these three headers unconditionally</b>, so the gateway must first strip any same-named headers from external requests before writing its own parsed values;
 * otherwise a client could send {@code X-Tenant-Id: 8} itself and impersonate merchant 8.
 */
public final class TenantHeaders {

    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String USER_ID = "X-User-Id";
    public static final String ROLE = "X-Role";

    public static final List<String> ALL = List.of(TENANT_ID, USER_ID, ROLE);

    /** User property name on RocketMQ messages (messages have no headers, so the tenant goes in a property) */
    public static final String MQ_TENANT_ID = "TENANT_ID";

    private TenantHeaders() {
    }
}
