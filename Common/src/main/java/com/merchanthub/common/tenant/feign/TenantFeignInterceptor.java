package com.merchanthub.common.tenant.feign;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Writes the current identity into request headers on Feign calls; the downstream {@code TenantContextInterceptor} reads them back --
 * this is how tenant identity crosses service boundaries. E.g. when a buyer places an order, order calls product-inventory to reserve stock and downstream still sees BUYER;
 * when inventory's dangling-reservation scan (SYSTEM) calls order to look up an order, order sees SYSTEM.
 * <p>
 * Note: Feign runs interceptors on the caller's thread by default, so the ThreadLocal is readable; if Feign ever gets Hystrix / thread-pool isolation,
 * the context must also be copied to the executing thread (Sentinel defaults to semaphore isolation, so it's unaffected).
 */
public class TenantFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // Clear any same-named header already on the template to avoid sending two values
        TenantHeaders.ALL.forEach(h -> template.removeHeader(h));
        TenantInfo info = TenantContext.get();
        if (info == null) {
            return;   // No context, no headers: downstream gets no identity and fails fast on business tables, which is safer than a wrong identity
        }
        template.header(TenantHeaders.ROLE, info.role().name());
        if (info.tenantId() != null) {
            template.header(TenantHeaders.TENANT_ID, String.valueOf(info.tenantId()));
        }
        if (info.userId() != null) {
            template.header(TenantHeaders.USER_ID, String.valueOf(info.userId()));
        }
    }
}
