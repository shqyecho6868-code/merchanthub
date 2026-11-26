package com.merchanthub.common.trace;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Servlet entry point: reuse / generate the traceId, keep it in the MDC during the request, always clear it afterwards */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    /** traceId seen in the MDC while business code runs */
    private final AtomicReference<String> seenInside = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> seenInside.set(MDC.get(TraceContext.MDC_KEY));

    @AfterEach
    void noLeak() {
        assertThat(MDC.get(TraceContext.MDC_KEY)).as("请求结束后 MDC 必须是干净的（Tomcat 线程复用）").isNull();
    }

    @Test
    @DisplayName("带了合法的 X-Trace-Id（网关写的）→ 原样沿用，写进 MDC 和响应头")
    void reusesIncomingHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders/1");
        req.addHeader(TraceContext.HEADER, "abcdef0123456789abcdef0123456789");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(seenInside.get()).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(res.getHeader(TraceContext.HEADER)).isEqualTo("abcdef0123456789abcdef0123456789");
    }

    @Test
    @DisplayName("没带 / 带了非法值（换行注入、太短）→ 生成新的 32 位十六进制")
    void generatesWhenMissingOrInvalid() throws Exception {
        for (String bad : new String[]{null, "short", "abc\nFAKE LOG LINE 0000"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
            if (bad != null) {
                req.addHeader(TraceContext.HEADER, bad);
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);

            assertThat(seenInside.get()).matches("[0-9a-f]{32}");
            assertThat(res.getHeader(TraceContext.HEADER)).isEqualTo(seenInside.get());
        }
    }

    @Test
    @DisplayName("业务抛异常 → MDC 照样被清掉（finally）")
    void clearsMdcOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        req.addHeader(TraceContext.HEADER, "trace-0001-boom");
        FilterChain boom = (rq, rs) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), boom))
                .isInstanceOf(IllegalStateException.class);
        // Assertion lives in @AfterEach noLeak
    }

    @Test
    @DisplayName("Scope 关闭时还原成外层的 traceId，而不是直接删掉")
    void scopeRestoresPrevious() {
        try (TraceContext.Scope outer = TraceContext.open("outer-trace-1")) {
            try (TraceContext.Scope inner = TraceContext.open("inner-trace-1")) {
                assertThat(TraceContext.current()).isEqualTo("inner-trace-1");
            }
            assertThat(TraceContext.current()).isEqualTo("outer-trace-1");
        }
    }
}
