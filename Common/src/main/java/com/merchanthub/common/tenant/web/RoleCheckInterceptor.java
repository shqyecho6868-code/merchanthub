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
 * 接口级 RBAC：看 Controller 方法（或类）上的 {@link RequireRole}，当前角色不在列表里 → 403。
 * 必须排在 {@link TenantContextInterceptor} 之后（先知道"你是谁"，才能判断"你能不能"）。
 * <p>
 * 用拦截器而不是 AOP：拦截器在参数绑定之前执行，越权请求连请求体都不解析就被拒绝。
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
            throw new UnauthorizedException("未登录");
        }
        Role[] allowed = rule.value();
        if (Arrays.stream(allowed).noneMatch(r -> r == info.role())) {
            throw new ForbiddenException("角色 " + info.role() + " 无权访问该接口");
        }
        return true;
    }
}
