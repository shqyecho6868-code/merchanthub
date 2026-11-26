package com.merchanthub.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 链路起点：沿用合法的 X-Trace-Id，否则生成；下游请求头和响应头是同一个值 */
class TraceIdGlobalFilterTest {

    private final TraceIdGlobalFilter filter = new TraceIdGlobalFilter();
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = ex -> {
        forwarded.set(ex);
        return Mono.empty();
    };

    private MockServerWebExchange run(MockServerHttpRequest req) {
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        filter.filter(ex, chain).block();
        return ex;
    }

    @Test
    @DisplayName("带了合法的 X-Trace-Id → 下游和响应头都沿用它")
    void reusesValidIncoming() {
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/1")
                .header("X-Trace-Id", "upstream-trace-0001").build());

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Trace-Id")).containsExactly("upstream-trace-0001");
        assertThat(ex.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo("upstream-trace-0001");
    }

    @Test
    @DisplayName("没带 → 生成 32 位十六进制，下游请求头和响应头一致")
    void generatesWhenMissing() {
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/1").build());

        String down = forwarded.get().getRequest().getHeaders().getFirst("X-Trace-Id");
        assertThat(down).matches("[0-9a-f]{32}");
        assertThat(ex.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo(down);
    }

    @Test
    @DisplayName("非法值（超长 / 带特殊字符）→ 覆盖成新生成的，下游只看到一个值")
    void replacesInvalid() {
        run(MockServerHttpRequest.get("/api/orders/1").header("X-Trace-Id", "bad id;drop").build());

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Trace-Id"))
                .hasSize(1)
                .allSatisfy(v -> assertThat(v).matches("[0-9a-f]{32}"));
    }
}
