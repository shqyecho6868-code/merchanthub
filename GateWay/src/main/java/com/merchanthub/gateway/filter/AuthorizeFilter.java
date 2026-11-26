package com.merchanthub.gateway.filter;


import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.security.JwtCodec;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Gateway authentication (reworked in C1). Does four things in order:
 * <ol>
 *   <li>Block /internal/**: internal service-to-service endpoints are for Feign only; external callers always get 404 (not 403, so we don't reveal "there's an endpoint here")</li>
 *   <li><b>First strip</b> any X-Tenant-Id / X-User-Id / X-Role sent by external requests: downstream trusts these three headers unconditionally, so without stripping identity could be forged</li>
 *   <li>Verify the Access-Token (signature + expiry + whether it is the currently valid one in Redis) and extract userId / role / tenantId</li>
 *   <li>Forward the identity downstream as request headers</li>
 * </ol>
 * Whitelisted paths (login, channel callbacks) skip steps 3 and 4 but still do steps 1 and 2 -- whitelisted endpoints must not receive forged identity headers either.
 */
@Slf4j
@Component
public class AuthorizeFilter implements GlobalFilter, Ordered {

    /**
     * C2: switched to the reactive client. The gateway is WebFlux, with only a few event-loop threads (about the number of CPU cores) handling all requests;
     * with the old blocking StringRedisTemplate, if Redis was 100ms slow, every request queued on that thread was delayed 100ms too
     */
    private final ReactiveStringRedisTemplate redis;
    /** D1: secret comes from merchanthub.jwt.secret, the same one user-tenant signs with */
    private final JwtCodec jwt;

    public AuthorizeFilter(ReactiveStringRedisTemplate redis, JwtCodec jwt) {
        this.redis = redis;
        this.jwt = jwt;
    }

    // Whitelisted paths: endpoints that don't require authentication
    static final List<String> EXCLUDE_PATHS = Arrays.asList(
            // D1: user-tenant's new login endpoint (the old IM SMS-code login was removed)
            "/api/user/login",
            "/api/user/register",
            "/api/user/refresh",
            // Async notifications from the payment channel: the channel has no user token; in reality this relies on signature verification (left over from B3); payment handles it internally as SYSTEM
            "/api/payments/callback/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 1. Internal endpoints are not exposed. Checked on "decoded path segments", so encoded bypasses like /api/%69nternal/... are caught too
        if (isInternalPath(request)) {
            log.warn("Rejected external access to internal endpoint path={}", path);
            return buildErrorResponse(exchange, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_ERROR, ErrorCode.NOT_FOUND_ERROR.getMessage());
        }

        // 2. Whether or not the request is let through later, first strip identity headers sent by the client
        ServerHttpRequest.Builder cleaned = request.mutate()
                .headers(h -> TenantHeaders.ALL.forEach(h::remove));

        if (EXCLUDE_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange.mutate().request(cleaned.build()).build());
        }

        String accessToken = request.getHeaders().getFirst("Access-Token");
        String refreshToken = request.getHeaders().getFirst("Refresh-Token");

        // 3. First reach an auth verdict (this may hit Redis, asynchronously), 4. then pass or reject based on it.
        // onErrorResume wraps only the "reach a verdict" part: if it wrapped chain.filter, downstream errors would also be rewritten to 401
        // defer: jwt.parse is synchronous; if it throws, the exception must become an error inside the Mono so the onErrorResume below can catch it
        return Mono.defer(() -> authenticate(accessToken, refreshToken))
                .onErrorResume(e -> {
                    log.error("System error from Redis / JWT during authentication", e);
                    return Mono.just(AuthResult.of(AuthOutcome.SYSTEM_ERROR));
                })
                .flatMap(result -> switch (result.outcome()) {
                    case PASS -> {
                        Identity id = result.identity();
                        cleaned.headers(h -> {
                            h.set(TenantHeaders.USER_ID, id.userId());
                            h.set(TenantHeaders.ROLE, id.role().name());
                            if (id.tenantId() != null) {
                                h.set(TenantHeaders.TENANT_ID, id.tenantId());
                            }
                        });
                        yield chain.filter(exchange.mutate().request(cleaned.build()).build());
                    }
                    case BAD_IDENTITY -> buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_INVALID.getMessage());
                    case NEED_REFRESH -> buildErrorResponse(exchange, ErrorCode.TOKEN_EXPIRED);
                    case NOT_LOGIN -> buildErrorResponse(exchange, ErrorCode.NOT_LOGIN_ERROR);
                    case SYSTEM_ERROR -> buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.SYSTEM_ERROR, "Failed to verify login status");
                });
    }

    /** Auth verdict */
    enum AuthOutcome { PASS, BAD_IDENTITY, NEED_REFRESH, NOT_LOGIN, SYSTEM_ERROR }

    record AuthResult(AuthOutcome outcome, Identity identity) {
        static AuthResult of(AuthOutcome outcome) {
            return new AuthResult(outcome, null);
        }
    }

    /**
     * Access-Token valid (signature ok, not expired, matches what's stored in Redis) -> PASS;
     * otherwise if Refresh-Token is valid -> NEED_REFRESH (tell the client to refresh); neither -> NOT_LOGIN
     */
    private Mono<AuthResult> authenticate(String accessToken, String refreshToken) {
        Claims acClaims = jwt.parse(accessToken);
        Mono<AuthResult> byAccess = acClaims == null ? Mono.empty()
                : sameAsStored(CommonConstant.ACCESS_TOKEN_PREFIX, acClaims.getSubject(), accessToken)
                .flatMap(same -> {
                    if (!same) {
                        return Mono.empty();
                    }
                    Identity id = Identity.from(acClaims);
                    if (id == null) {
                        log.warn("Invalid identity in token userId={} role={} tenantId={}", acClaims.getSubject(),
                                acClaims.get(JwtCodec.CLAIM_ROLE), acClaims.get(JwtCodec.CLAIM_TENANT_ID));
                        return Mono.just(AuthResult.of(AuthOutcome.BAD_IDENTITY));
                    }
                    return Mono.just(new AuthResult(AuthOutcome.PASS, id));
                });
        // defer: when the Access-Token passes, the Refresh-Token is never parsed / looked up
        return byAccess.switchIfEmpty(Mono.defer(() -> {
            Claims rfClaims = jwt.parse(refreshToken);
            if (rfClaims == null) {
                return Mono.just(AuthResult.of(AuthOutcome.NOT_LOGIN));
            }
            return sameAsStored(CommonConstant.REFRESH_TOKEN_PREFIX, rfClaims.getSubject(), refreshToken)
                    .map(same -> AuthResult.of(same ? AuthOutcome.NEED_REFRESH : AuthOutcome.NOT_LOGIN));
        }));
    }

    /** Whether Redis holds this exact token (if not -> logged out / kicked out). Returns false rather than an empty Mono when the key doesn't exist */
    private Mono<Boolean> sameAsStored(String prefix, String userId, String token) {
        if (userId == null) {
            return Mono.just(false);
        }
        return redis.opsForValue().get(prefix + userId).map(token::equals).defaultIfEmpty(false);
    }

    /** Any path segment (decoded, case-insensitive) equal to internal marks an internal endpoint */
    static boolean isInternalPath(ServerHttpRequest request) {
        return request.getPath().elements().stream()
                .filter(e -> e instanceof PathContainer.PathSegment)
                .map(e -> ((PathContainer.PathSegment) e).valueToMatch())
                .anyMatch("internal"::equalsIgnoreCase);
    }

    /**
     * Identity extracted from the token. Same rules as the downstream TenantContextInterceptor:
     * merchant roles must have a tenantId; buyers / platform admins must not; SYSTEM must never appear in a user token (internal services only)
     */
    record Identity(String userId, Role role, String tenantId) {
        static Identity from(Claims claims) {
            String userId = claims.getSubject();
            Role role = Role.parse(claims.get(JwtCodec.CLAIM_ROLE, String.class));
            Object tenantClaim = claims.get(JwtCodec.CLAIM_TENANT_ID);
            String tenantId = tenantClaim == null ? null : String.valueOf(tenantClaim);
            if (userId == null || !userId.matches("\\d+") || role == null || !role.isUserRole()) {
                return null;
            }
            if (tenantId != null && !tenantId.matches("\\d+")) {
                return null;
            }
            if (role.isMerchant() != (tenantId != null)) {
                return null;
            }
            return new Identity(userId, role, tenantId);
        }
    }

    private Mono<Void> buildErrorResponse(ServerWebExchange exchange, ErrorCode errorCode) {
        return buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, errorCode, errorCode.getMessage());
    }

    private Mono<Void> buildErrorResponse(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode, String message) {
        String jsonResponse = String.format("{\"code\":%d,\"message\":\"%s\"}", errorCode.getCode(), message);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(jsonResponse.getBytes(StandardCharsets.UTF_8));

        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // relatively high precedence
    }
}
