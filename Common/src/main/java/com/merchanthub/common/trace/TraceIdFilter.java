package com.merchanthub.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Entry point for Servlet services: puts the gateway's X-Trace-Id into the MDC (or generates one if absent, e.g. direct calls, health checks),
 * and echoes it in the response header -- with the traceId from the response, the caller can search the logs for the whole trace.
 * <p>
 * Why a Filter rather than a HandlerInterceptor like TenantContextInterceptor: the Filter wraps DispatcherServlet,
 * so logs from argument binding failures, exceptions thrown by interceptors and GlobalExceptionHandler all happen inside it and carry the traceId;
 * and finally always runs, so the MDC is always restored (Tomcat reuses threads; without restoring it leaks into the next request).
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (TraceContext.Scope scope = TraceContext.open(request.getHeader(TraceContext.HEADER))) {
            // Write before the chain: once the response is committed (body writing starts), headers can no longer be added
            response.setHeader(TraceContext.HEADER, scope.traceId());
            chain.doFilter(request, response);
        }
    }
}
