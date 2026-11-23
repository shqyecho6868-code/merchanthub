package com.merchanthub.common.tenant.web;

import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求进来：把网关注入的 X-Tenant-Id / X-User-Id / X-Role 写进 {@link TenantContext}；
 * 请求结束（afterCompletion，无论成功还是抛异常都会调用）：还原线程上下文。
 * <p>
 * 没带 X-Role 的请求（如渠道回调、健康检查）不设置上下文：这时如果去查业务表，租户插件会直接报错（fail-closed），
 * 需要跨租户的入口自己显式 {@link TenantContext#runAsSystem}。
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    static final String SCOPE_ATTR = TenantContextInterceptor.class.getName() + ".scope";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantInfo info = fromHeaders(request);
        if (info != null) {
            // 最后一步才 open：前面校验抛异常时 afterCompletion 不会被调用，先 open 就会把上下文漏在线程上
            request.setAttribute(SCOPE_ATTR, TenantContext.open(info));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object scope = request.getAttribute(SCOPE_ATTR);
        if (scope instanceof TenantContext.Scope s) {
            request.removeAttribute(SCOPE_ATTR);
            s.close();
        }
    }

    /** 请求头 → 身份。头是网关写的，格式不对说明请求绕过了网关或网关有 bug，直接拒绝 */
    static TenantInfo fromHeaders(HttpServletRequest request) {
        String roleHeader = request.getHeader(TenantHeaders.ROLE);
        if (roleHeader == null || roleHeader.isBlank()) {
            return null;
        }
        Role role = Role.parse(roleHeader);
        if (role == null) {
            throw new UnauthorizedException("非法的角色: " + roleHeader);
        }
        Long tenantId = parseLong(request.getHeader(TenantHeaders.TENANT_ID), TenantHeaders.TENANT_ID);
        Long userId = parseLong(request.getHeader(TenantHeaders.USER_ID), TenantHeaders.USER_ID);
        if (role.isMerchant() && tenantId == null) {
            throw new UnauthorizedException("商家角色缺少 " + TenantHeaders.TENANT_ID);
        }
        if ((role == Role.BUYER || role == Role.PLATFORM_ADMIN) && tenantId != null) {
            // 买家 / 平台管理员不属于商家：带了 tenantId 说明身份信息自相矛盾，不猜按哪个算
            throw new UnauthorizedException(role + " 不应携带 " + TenantHeaders.TENANT_ID);
        }
        return new TenantInfo(tenantId, userId, role);
    }

    private static Long parseLong(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("非法的 " + name + ": " + value);
        }
    }
}
