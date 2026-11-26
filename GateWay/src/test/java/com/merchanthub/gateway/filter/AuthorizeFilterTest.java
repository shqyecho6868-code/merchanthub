package com.merchanthub.gateway.filter;

import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.security.JwtCodec;
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
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gateway auth: strip forged headers, inject identity headers, block /internal/** (no containers; Redis is a stub) */
class AuthorizeFilterTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
    private final JwtCodec jwt = new JwtCodec(Base64.getEncoder().encodeToString("gateway-test-secret-0123456789abc".getBytes()));
    private final AuthorizeFilter filter = new AuthorizeFilter(redis, jwt);

    /** The request actually received downstream after the filter lets it through */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = ex -> {
        forwarded.set(ex);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    /** Simulate login: issue a token and store it in Redis (the gateway checks that Redis holds this exact token) */
    private String login(long userId, Long tenantId, String role) {
        String token = jwt.issue(userId, tenantId, role, Duration.ofMinutes(10));
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
    @DisplayName("Merchant staff token -> downstream gets X-Tenant-Id / X-User-Id / X-Role; client-forged X-Tenant-Id is overwritten")
    void merchantToken_injectsIdentity_overridesForgedHeader() {
        String token = login(2002L, 7L, "MERCHANT_STAFF");
        run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", token)
                .header("X-Tenant-Id", "8").header("X-Role", "PLATFORM_ADMIN").build());

        assertThat(forwardedHeaders().get("X-Tenant-Id")).containsExactly("7");
        assertThat(forwardedHeaders().get("X-User-Id")).containsExactly("2002");
        assertThat(forwardedHeaders().get("X-Role")).containsExactly("MERCHANT_STAFF");
    }

    @Test
    @DisplayName("Buyer token: no X-Tenant-Id (a client-supplied one is removed too)")
    void buyerToken_noTenantHeader() {
        String token = login(50001L, null, "BUYER");
        run(MockServerHttpRequest.get("/api/orders/mine/1").header("Access-Token", token).header("X-Tenant-Id", "7").build());
        assertThat(forwardedHeaders().containsKey("X-Tenant-Id")).isFalse();
        assertThat(forwardedHeaders().getFirst("X-Role")).isEqualTo("BUYER");
    }

    @Test
    @DisplayName("Whitelisted path (channel callback) passes, but forged identity headers are still removed")
    void whitelist_stillStripsHeaders() {
        run(MockServerHttpRequest.post("/api/payments/callback/mock")
                .header("X-Tenant-Id", "7").header("X-User-Id", "1").header("X-Role", "SYSTEM").build());
        assertThat(forwardedHeaders().containsKey("X-Tenant-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-User-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-Role")).isFalse();
    }

    @Test
    @DisplayName("/internal/** always 404, encoded bypass blocked too (%69 = i)")
    void internalPathBlocked() {
        String token = login(1L, null, "PLATFORM_ADMIN");
        for (String path : new String[]{"/internal/orders/1", "/api/internal/orders/1", "/api/%69nternal/orders/1",
                "/api/INTERNAL/skus/query"}) {
            forwarded.set(null);
            // URI.create keeps the raw %69 so the test client doesn't encode it again
            MockServerWebExchange ex = run(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path))
                    .header("Access-Token", token).build());
            assertThat(ex.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(forwarded.get()).as(path).isNull();
        }
    }

    @Test
    @DisplayName("No token -> 401; token with role SYSTEM / merchant without tenantId -> 401")
    void invalidIdentity_unauthorized() {
        MockServerWebExchange none = run(MockServerHttpRequest.get("/api/orders/1").build());
        assertThat(none.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String system = login(3L, null, "SYSTEM");
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", system).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String merchantNoTenant = login(4L, null, "MERCHANT_ADMIN");
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", merchantNoTenant).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Signature is valid, but Redis holds a different token (logged out / kicked out by a newer login)
        String stale = jwt.issue(5L, 7L, "MERCHANT_ADMIN", Duration.ofMinutes(10));
        when(ops.get(anyString())).thenReturn(Mono.empty());
        assertThat(run(MockServerHttpRequest.get("/api/orders/1").header("Access-Token", stale).build())
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Access-Token expired but Refresh-Token valid -> 401 + TOKEN_EXPIRED (prompt refresh)")
    void refreshTokenValid_needRefresh() {
        String refresh = jwt.issue(6L, null, "BUYER", Duration.ofMinutes(10));
        when(ops.get(anyString())).thenReturn(Mono.empty());
        when(ops.get(CommonConstant.REFRESH_TOKEN_PREFIX + 6L)).thenReturn(Mono.just(refresh));
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/orders/mine/1")
                .header("Access-Token", "garbage").header("Refresh-Token", refresh).build());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getBodyAsString().block()).contains("\"code\":40103");
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("Redis error -> 401 system error, request not passed through; downstream errors are not rewritten to 401")
    void redisError_unauthorized_downstreamErrorNotSwallowed() {
        String token = jwt.issue(7L, null, "BUYER", Duration.ofMinutes(10));
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
