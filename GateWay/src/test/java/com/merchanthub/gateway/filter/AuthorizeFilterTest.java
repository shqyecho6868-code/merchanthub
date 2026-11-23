package com.merchanthub.gateway.filter;

import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 网关鉴权：清洗伪造头、注入身份头、挡 /internal/**（不起容器，Redis 用替身） */
class AuthorizeFilterTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
    private final AuthorizeFilter filter = new AuthorizeFilter(redis);

    /** 放行后下游实际收到的请求 */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = ex -> {
        forwarded.set(ex);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    /** 模拟登录：签发 token 并写入 Redis（网关会核对 Redis 里存的是不是这个 token） */
    private String login(long userId, Long tenantId, String role) {
        String token = JwtUtil.generate(userId, tenantId, role, 10, TimeUnit.MINUTES);
        when(ops.get(CommonConstant.ACCESS_TOKEN_PREFIX + userId)).thenReturn(Mono.just(token));
        return token;
    }

    private MockServerWebExchange run(MockServerHttpRequest req) {
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        filter.filter(ex, chain).block();
        return ex;
    }

    private HttpHeaders forwardedHeaders() {
        return forwarded.get().getRequest().getHeaders();
    }

    @Test
    @DisplayName("商家员工 token → 下游收到 X-Tenant-Id / X-User-Id / X-Role；客户端伪造的 X-Tenant-Id 被覆盖")
    void merchantToken_injectsIdentity_overridesForgedHeader() {
        String token = login(2002L, 7L, "MERCHANT_STAFF");
        run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", token)
                .header("X-Tenant-Id", "8").header("X-Role", "PLATFORM_ADMIN").build());

        assertThat(forwardedHeaders().get("X-Tenant-Id")).containsExactly("7");
        assertThat(forwardedHeaders().get("X-User-Id")).containsExactly("2002");
        assertThat(forwardedHeaders().get("X-Role")).containsExactly("MERCHANT_STAFF");
    }

    @Test
    @DisplayName("买家 token：不带 X-Tenant-Id（客户端自带的也删掉）")
    void buyerToken_noTenantHeader() {
        String token = login(50001L, null, "BUYER");
        run(MockServerHttpRequest.get("/api/orders/mine/1").header("Access-Token", token).header("X-Tenant-Id", "7").build());
        assertThat(forwardedHeaders().containsKey("X-Tenant-Id")).isFalse();
        assertThat(forwardedHeaders().getFirst("X-Role")).isEqualTo("BUYER");
    }

    @Test
    @DisplayName("白名单（渠道回调）放行，但伪造的身份头照样被删")
    void whitelist_stillStripsHeaders() {
        run(MockServerHttpRequest.post("/api/payments/callback/mock")
                .header("X-Tenant-Id", "7").header("X-User-Id", "1").header("X-Role", "SYSTEM").build());
        assertThat(forwardedHeaders().containsKey("X-Tenant-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-User-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-Role")).isFalse();
    }

    @Test
    @DisplayName("/internal/** 一律 404，编码绕过也挡（%69 = i）")
    void internalPathBlocked() {
        String token = login(1L, null, "PLATFORM_ADMIN");
        for (String path : new String[]{"/internal/orders/1", "/api/internal/orders/1", "/api/%69nternal/orders/1",
                "/api/INTERNAL/skus/query"}) {
            forwarded.set(null);
            // 用 URI.create 保留原样的 %69，不让测试工具再编码一次
            MockServerWebExchange ex = run(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path))
                    .header("Access-Token", token).build());
            assertThat(ex.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(forwarded.get()).as(path).isNull();
        }
    }

    @Test
    @DisplayName("没 token → 401；token 里角色是 SYSTEM / 商家没 tenantId → 401")
    void invalidIdentity_unauthorized() {
        MockServerWebExchange none = run(MockServerHttpRequest.get("/api/orders/1").build());
        assertThat(none.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String system = login(3L, null, "SYSTEM");
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", system).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String merchantNoTenant = login(4L, null, "MERCHANT_ADMIN");
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", merchantNoTenant).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 签名对、但 Redis 里不是这个 token（已登出 / 被挤下线）
        String stale = JwtUtil.generate(5L, 7L, "MERCHANT_ADMIN", 10, TimeUnit.MINUTES);
        when(ops.get(anyString())).thenReturn(Mono.empty());
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", stale).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Access-Token 已失效但 Refresh-Token 有效 → 401 + TOKEN_EXPIRED（提示刷新）")
    void refreshTokenValid_needRefresh() {
        String refresh = JwtUtil.generate(6L, null, "BUYER", 10, TimeUnit.MINUTES);
        when(ops.get(anyString())).thenReturn(Mono.empty());
        when(ops.get(CommonConstant.REFRESH_TOKEN_PREFIX + 6L)).thenReturn(Mono.just(refresh));
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/mine/1")
                .header("Access-Token", "garbage").header("Refresh-Token", refresh).build());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getBodyAsString().block()).contains("\"code\":40103");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("C2：Redis 出错 → 401 系统异常，不放行；下游报错不会被改写成 401")
    void redisError_unauthorized_downstreamErrorNotSwallowed() {
        String token = JwtUtil.generate(7L, null, "BUYER", 10, TimeUnit.MINUTES);
        when(ops.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/mine/1").header("Access-Token", token).build());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();

        String ok = login(8L, null, "BUYER");
        GatewayFilterChain failing = e -> Mono.error(new IllegalStateException("downstream boom"));
        MockServerWebExchange ex2 = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/mine/1")
                .header("Access-Token", ok).build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.filter(ex2, failing).block())
                .hasMessageContaining("downstream boom");
    }
}
