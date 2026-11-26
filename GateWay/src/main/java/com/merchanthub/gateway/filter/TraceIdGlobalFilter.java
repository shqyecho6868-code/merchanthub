package com.merchanthub.gateway.filter;

import com.merchanthub.common.trace.TraceContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 链路起点：给每个请求定一个 traceId，写进转发给下游的 X-Trace-Id 头和返回给客户端的响应头。
 * <ul>
 *   <li>客户端 / 上游（Nginx、ALB）带了合法的 X-Trace-Id 就沿用，方便和上游日志对上；不合法（超长、带换行）就丢掉重新生成</li>
 *   <li>最高优先级，排在 AuthorizeFilter 前面：鉴权失败的 401 响应也带 traceId，用户报"登不上"时能直接查</li>
 * </ul>
 * 为什么网关自己不往 MDC 里放：WebFlux 一个请求会在多个 event-loop 线程之间跳，ThreadLocal（MDC）跟不住，
 * 要做得引入 Reactor Context + Micrometer 的上下文传播，成本和收益不成比例；网关日志很少，靠响应头的 traceId 去下游查即可。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** 生效的 traceId 也放进 exchange 属性，网关里其他 Filter 打日志时需要可以取 */
    public static final String ATTR = TraceIdGlobalFilter.class.getName() + ".traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.reuseOrNew(exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER));
        // set 而不是 add：外部带了非法值时直接覆盖掉，下游只会看到一个值
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(TraceContext.HEADER, traceId))
                .build();
        // 响应头在进入链之前写：响应提交后不能再改头（下游响应头里同名的值会覆盖成同一个 ID，不会出现两个）
        exchange.getResponse().getHeaders().set(TraceContext.HEADER, traceId);
        exchange.getAttributes().put(ATTR, traceId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
