package com.merchanthub.common.tenant.feign;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用时把当前身份写进请求头，下游的 {@code TenantContextInterceptor} 再读出来 ——
 * 跨服务调用的租户透传就靠这一步。例：买家下单时 order 调 product-inventory 预占，下游看到的仍是 BUYER；
 * inventory 的悬空扫描（SYSTEM）调 order 查订单，order 看到的是 SYSTEM。
 * <p>
 * 注意：Feign 默认在调用方线程里执行拦截器，所以能读到 ThreadLocal；如果以后给 Feign 开了 Hystrix / 线程池隔离，
 * 要额外把上下文复制到执行线程（Sentinel 默认信号量隔离，不受影响）。
 */
public class TenantFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 先清掉模板里可能已有的同名头，避免出现两个值
        TenantHeaders.ALL.forEach(h -> template.removeHeader(h));
        TenantInfo info = TenantContext.get();
        if (info == null) {
            return;   // 没有上下文就不带：下游拿不到身份，碰业务表会直接报错，比带错身份安全
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
