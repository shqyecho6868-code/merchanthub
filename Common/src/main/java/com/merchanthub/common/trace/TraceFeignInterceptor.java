package com.merchanthub.common.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用时把当前 traceId 写进 X-Trace-Id，下游的 {@link TraceIdFilter} 读出来继续用同一个 ID。
 * 例：下单时 order 调 product-inventory 预占库存，两边日志里是同一个 traceId。
 * <p>
 * 和 TenantFeignInterceptor 一样依赖"拦截器在调用方线程执行"才能读到 MDC（Sentinel 信号量隔离不换线程）。
 */
public class TraceFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        template.removeHeader(TraceContext.HEADER);   // 避免模板里已有的值和当前值同时出现
        String traceId = TraceContext.current();
        if (TraceContext.isValid(traceId)) {
            template.header(TraceContext.HEADER, traceId);
        }
        // 没有 traceId（如某个没包 Scope 的后台线程）就不带：下游会自己生成，链路断开但不报错
    }
}
