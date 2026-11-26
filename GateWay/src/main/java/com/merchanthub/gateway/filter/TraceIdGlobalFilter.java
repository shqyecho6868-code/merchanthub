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
 * Start of the trace: assign each request a traceId and write it to the X-Trace-Id header forwarded downstream and to the response header returned to the client.
 * <ul>
 *   <li>If the client / upstream (Nginx, ALB) sends a valid X-Trace-Id, reuse it so logs line up with upstream; if invalid (too long, contains newlines), drop it and generate a new one</li>
 *   <li>Highest precedence, ahead of AuthorizeFilter: even 401 auth failures carry a traceId, so a "can't log in" report can be looked up directly</li>
 * </ul>
 * Why the gateway itself doesn't use MDC: in WebFlux a request hops across several event-loop threads, so a ThreadLocal (MDC) can't follow it;
 * doing it properly needs Reactor Context + Micrometer context propagation, which isn't worth it. Gateway logs are sparse; use the traceId in the response header to search downstream.
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** The effective traceId is also stored as an exchange attribute so other gateway filters can read it when logging */
    public static final String ATTR = TraceIdGlobalFilter.class.getName() + ".traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.reuseOrNew(exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER));
        // set instead of add: an invalid external value is simply overwritten, so downstream only ever sees one value
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(TraceContext.HEADER, traceId))
                .build();
        // Write the response header before entering the chain: headers can't be changed after the response is committed (a same-named downstream header ends up with the same ID, never two)
        exchange.getResponse().getHeaders().set(TraceContext.HEADER, traceId);
        exchange.getAttributes().put(ATTR, traceId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
