package com.merchanthub.common.tenant;

import java.util.List;

/**
 * 网关解析 JWT 后注入、服务间 Feign 透传的身份请求头（C1）。
 * <p>
 * 下游服务<b>无条件信任</b>这三个头，所以网关必须先删掉外部请求自带的同名头，再写入自己解析出的值；
 * 否则客户端自己带一个 {@code X-Tenant-Id: 8} 就能冒充商家 8。
 */
public final class TenantHeaders {

    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String USER_ID = "X-User-Id";
    public static final String ROLE = "X-Role";

    public static final List<String> ALL = List.of(TENANT_ID, USER_ID, ROLE);

    /** RocketMQ 消息的 user property 名（消息没有请求头，租户放在属性里） */
    public static final String MQ_TENANT_ID = "TENANT_ID";

    private TenantHeaders() {
    }
}
