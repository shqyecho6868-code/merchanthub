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

/** Start of the trace: reuse a valid X-Trace-Id, otherwise generate one; the downstream request header and the response header carry the same value */
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
    @DisplayName("Valid X-Trace-Id provided -> both downstream and response header reuse it")
    void reusesValidIncoming() {
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/1")
                .header("X-Trace-Id", "upstream-trace-0001").build());

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Trace-Id")).containsExactly("upstream-trace-0001");
        assertThat(ex.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo("upstream-trace-0001");
    }

    @Test
    @DisplayName("None provided -> generate 32 hex chars; downstream request header and response header match")
    void generatesWhenMissing() {
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/1").build());

        String down = forwarded.get().getRequest().getHeaders().getFirst("X-Trace-Id");
        assertThat(down).matches("[0-9a-f]{32}");
        assertThat(ex.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo(down);
    }

    @Test
    @DisplayName("Invalid value (too long / special chars) -> overwritten with a new one; downstream sees exactly one value")
    void replacesInvalid() {
        run(MockServerHttpRequest.get("/api/orders/1").header("X-Trace-Id", "bad id;drop").build());

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Trace-Id"))
                .hasSize(1)
                .allSatisfy(v -> assertThat(v).matches("[0-9a-f]{32}"));
    }
}
