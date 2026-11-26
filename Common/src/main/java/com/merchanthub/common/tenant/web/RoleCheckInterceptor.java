package com.merchanthub.common.tenant.web;

import com.merchanthub.common.exception.ForbiddenException;
import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * Endpoint-level RBAC: checks {@link RequireRole} on the Controller method (or class); current role not in the list → 403.
 * Must run after {@link TenantContextInterceptor} (we need to know "who you are" before deciding "what you may do").
 * <p>
 * An interceptor rather than AOP: interceptors run before argument binding, so unauthorized requests are rejected without even parsing the body.
 */
public class RoleCheckInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequireRole rule = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequireRole.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequireRole.class);
        }
        if (rule == null) {
            return true;
        }
        TenantInfo info = TenantContext.get();
        if (info == null) {
            throw new UnauthorizedException("Not logged in");
        }
        Role[] allowed = rule.value();
        if (Arrays.stream(allowed).noneMatch(r -> r == info.role())) {
            throw new ForbiddenException("Role " + info.role() + " is not allowed to access this endpoint");
        }
        return true;
    }
}
