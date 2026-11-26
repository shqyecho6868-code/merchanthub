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
 * On request: writes the gateway-injected X-Tenant-Id / X-User-Id / X-Role into {@link TenantContext};
 * on completion (afterCompletion, called on success and on exceptions alike): restores the thread context.
 * <p>
 * Requests without X-Role (e.g. channel callbacks, health checks) get no context: if they then hit a business table, the tenant plugin throws (fail-closed);
 * entry points that need to cross tenants call {@link TenantContext#runAsSystem} explicitly.
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    static final String SCOPE_ATTR = TenantContextInterceptor.class.getName() + ".scope";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantInfo info = fromHeaders(request);
        if (info != null) {
            // open only as the last step: if validation above throws, afterCompletion isn't called, so opening earlier would leak the context on the thread
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

    /** Headers → identity. The gateway writes these headers; a bad format means the request bypassed the gateway or the gateway has a bug, so reject outright */
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
            // Buyers / platform admins belong to no merchant: carrying a tenantId means the identity is self-contradictory, so don't guess which one applies
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
