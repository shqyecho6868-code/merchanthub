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
        assertThat(MDC.get(TraceContext.MDC_KEY)).as("MDC must be clean after the request (Tomcat reuses threads)").isNull();
    }

    @Test
    @DisplayName("Valid X-Trace-Id (written by the gateway) → reused as-is, written into the MDC and response header")
    void reusesIncomingHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders/1");
        req.addHeader(TraceContext.HEADER, "abcdef0123456789abcdef0123456789");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(seenInside.get()).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(res.getHeader(TraceContext.HEADER)).isEqualTo("abcdef0123456789abcdef0123456789");
    }

    @Test
    @DisplayName("Missing / invalid value (newline injection, too short) → generate a new 32-char hex ID")
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
    @DisplayName("Business code throws → MDC still cleared (finally)")
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
    @DisplayName("Closing a Scope restores the outer traceId instead of just removing it")
    void scopeRestoresPrevious() {
        try (TraceContext.Scope outer = TraceContext.open("outer-trace-1")) {
            try (TraceContext.Scope inner = TraceContext.open("inner-trace-1")) {
                assertThat(TraceContext.current()).isEqualTo("inner-trace-1");
            }
            assertThat(TraceContext.current()).isEqualTo("outer-trace-1");
        }
    }
}
