package com.merchanthub.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet 服务的入口：读网关写的 X-Trace-Id 放进 MDC（没带就生成一个，比如直接调服务、健康检查），
 * 同时写回响应头 —— 调用方拿着响应头里的 traceId 就能去日志里搜整条链路。
 * <p>
 * 为什么用 Filter 而不是像 TenantContextInterceptor 那样用 HandlerInterceptor：Filter 在 DispatcherServlet 外层，
 * 参数绑定失败、拦截器自己抛异常、GlobalExceptionHandler 打的日志都在它里面，都能带上 traceId；
 * 并且 finally 一定执行，MDC 一定被还原（Tomcat 线程复用，不还原就串到下一个请求）。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (TraceContext.Scope scope = TraceContext.open(request.getHeader(TraceContext.HEADER))) {
            // 在 chain 之前写：响应一旦提交（开始写 body）就不能再加头了
            response.setHeader(TraceContext.HEADER, scope.traceId());
            chain.doFilter(request, response);
        }
    }
}
