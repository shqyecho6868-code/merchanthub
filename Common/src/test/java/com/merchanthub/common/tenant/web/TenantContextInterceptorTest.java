package com.merchanthub.common.tenant.web;

import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextInterceptorTest {

    private final TenantContextInterceptor interceptor = new TenantContextInterceptor();
    private final MockHttpServletResponse resp = new MockHttpServletResponse();

    @AfterEach
    void noLeak() {
        assertThat(TenantContext.get()).isNull();
    }

    private static MockHttpServletRequest req(String tenant, String user, String role) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/orders/1");
        if (tenant != null) r.addHeader("X-Tenant-Id", tenant);
        if (user != null) r.addHeader("X-User-Id", user);
        if (role != null) r.addHeader("X-Role", role);
        return r;
    }

    @Test
    @DisplayName("请求头 → ThreadLocal；请求结束（含异常结束）→ 清掉")
    void headersToContextAndCleared() {
        MockHttpServletRequest r = req("7", "2001", "MERCHANT_STAFF");
        interceptor.preHandle(r, resp, null);
        assertThat(TenantContext.get()).isEqualTo(new TenantInfo(7L, 2001L, Role.MERCHANT_STAFF));

        // 业务抛了异常，afterCompletion 照样会被 DispatcherServlet 调用
        interceptor.afterCompletion(r, resp, null, new RuntimeException("业务失败"));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("没带 X-Role（渠道回调、健康检查）：不设上下文")
    void noRoleNoContext() {
        MockHttpServletRequest r = req(null, null, null);
        interceptor.preHandle(r, resp, null);
        assertThat(TenantContext.get()).isNull();
        interceptor.afterCompletion(r, resp, null, null);
    }

    @Test
    @DisplayName("身份头自相矛盾：拒绝且不在线程上留下任何东西")
    void invalidHeadersRejected() {
        assertThatThrownBy(() -> interceptor.preHandle(req(null, "2001", "MERCHANT_ADMIN"), resp, null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> interceptor.preHandle(req("7", "50001", "BUYER"), resp, null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> interceptor.preHandle(req("abc", "1", "MERCHANT_ADMIN"), resp, null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> interceptor.preHandle(req(null, "1", "ROOT"), resp, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("同一个线程连续处理两个请求：第二个请求看不到第一个的身份")
    void sameThreadTwoRequests() {
        MockHttpServletRequest a = req("7", "2001", "MERCHANT_ADMIN");
        interceptor.preHandle(a, resp, null);
        interceptor.afterCompletion(a, resp, null, null);

        MockHttpServletRequest b = req(null, null, null);   // 没带身份的请求
        interceptor.preHandle(b, resp, null);
        assertThat(TenantContext.get()).isNull();
        interceptor.afterCompletion(b, resp, null, null);
    }
}
