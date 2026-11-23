package com.merchanthub.gateway.filter;


import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.utils.JwtUtil;

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
 * 网关鉴权（C1 改造）。按顺序做四件事：
 * <ol>
 *   <li>挡掉 /internal/**：服务间内部接口只给 Feign 用，外部一律 404（不说 403：不告诉外部"这里有个接口"）</li>
 *   <li><b>先删</b>外部请求自带的 X-Tenant-Id / X-User-Id / X-Role：下游无条件信任这三个头，不删就能伪造身份</li>
 *   <li>校验 Access-Token（签名 + 过期 + Redis 里是不是当前有效的那个），取出 userId / role / tenantId</li>
 *   <li>把身份写成请求头往下游透传</li>
 * </ol>
 * 白名单路径（登录、渠道回调）跳过第 3、4 步，但第 1、2 步照做 —— 白名单接口同样不能收到伪造的身份头。
 */
@Slf4j
@Component
public class AuthorizeFilter implements GlobalFilter, Ordered {

    /**
     * C2：换成响应式客户端。网关是 WebFlux，只有少量 event-loop 线程（约等于 CPU 核数）在处理所有请求；
     * 之前用阻塞的 StringRedisTemplate，Redis 慢 100ms，这个线程上排队的所有请求都跟着卡 100ms
     */
    private final ReactiveStringRedisTemplate redis;

    public AuthorizeFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    // 白名单路径：不需要认证的接口
    static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/api/user/login/code",
            "/api/user/register",
            "/api/user/sendCaptcha",
            "/api/user/login/password",
            "/api/user/refresh",
            // 支付渠道的异步通知：渠道没有用户 token，真实场景靠验签（B3 遗留），payment 内部以 SYSTEM 身份处理
            "/api/payments/callback/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 1. 内部接口不对外。按"解码后的路径段"判断，/api/%69nternal/... 这类编码绕过也能识别
        if (isInternalPath(request)) {
            log.warn("拒绝外部访问内部接口 path={}", path);
            return buildErrorResponse(exchange, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_ERROR, ErrorCode.NOT_FOUND_ERROR.getMessage());
        }

        // 2. 不管后面放不放行，先清掉客户端自带的身份头
        ServerHttpRequest.Builder cleaned = request.mutate()
                .headers(h -> TenantHeaders.ALL.forEach(h::remove));

        if (EXCLUDE_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange.mutate().request(cleaned.build()).build());
        }

        String accessToken = request.getHeaders().getFirst("Access-Token");
        String refreshToken = request.getHeaders().getFirst("Refresh-Token");

        // 3. 先得出鉴权结论（这一步可能查 Redis，异步），4. 再按结论放行或拒绝。
        // onErrorResume 只包住"得出结论"这一段：如果包在 chain.filter 外面，下游的报错也会被改写成 401
        // defer：JwtUtil.parse 是同步调用，万一抛异常也要变成 Mono 里的错误，才能被下面的 onErrorResume 接住
        return Mono.defer(() -> authenticate(accessToken, refreshToken))
                .onErrorResume(e -> {
                    log.error("鉴权时 Redis / JWT 系统异常", e);
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
                    case SYSTEM_ERROR -> buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.SYSTEM_ERROR, "登录状态确认失败");
                });
    }

    /** 鉴权结论 */
    enum AuthOutcome { PASS, BAD_IDENTITY, NEED_REFRESH, NOT_LOGIN, SYSTEM_ERROR }

    record AuthResult(AuthOutcome outcome, Identity identity) {
        static AuthResult of(AuthOutcome outcome) {
            return new AuthResult(outcome, null);
        }
    }

    /**
     * Access-Token 有效（签名对、没过期、和 Redis 里存的一致）→ PASS；
     * 否则 Refresh-Token 有效 → NEED_REFRESH（提示前端去刷新）；都不行 → NOT_LOGIN
     */
    private Mono<AuthResult> authenticate(String accessToken, String refreshToken) {
        Claims acClaims = JwtUtil.parse(accessToken);
        Mono<AuthResult> byAccess = acClaims == null ? Mono.empty()
                : sameAsStored(CommonConstant.ACCESS_TOKEN_PREFIX, acClaims.getSubject(), accessToken)
                .flatMap(same -> {
                    if (!same) {
                        return Mono.empty();
                    }
                    Identity id = Identity.from(acClaims);
                    if (id == null) {
                        log.warn("token 身份信息不合法 userId={} role={} tenantId={}", acClaims.getSubject(),
                                acClaims.get(JwtUtil.CLAIM_ROLE), acClaims.get(JwtUtil.CLAIM_TENANT_ID));
                        return Mono.just(AuthResult.of(AuthOutcome.BAD_IDENTITY));
                    }
                    return Mono.just(new AuthResult(AuthOutcome.PASS, id));
                });
        // defer：Access-Token 通过时，根本不去解析 / 查 Refresh-Token
        return byAccess.switchIfEmpty(Mono.defer(() -> {
            Claims rfClaims = JwtUtil.parse(refreshToken);
            if (rfClaims == null) {
                return Mono.just(AuthResult.of(AuthOutcome.NOT_LOGIN));
            }
            return sameAsStored(CommonConstant.REFRESH_TOKEN_PREFIX, rfClaims.getSubject(), refreshToken)
                    .map(same -> AuthResult.of(same ? AuthOutcome.NEED_REFRESH : AuthOutcome.NOT_LOGIN));
        }));
    }

    /** Redis 里存的是不是这个 token（不是 → 已登出 / 被挤下线）。key 不存在时返回 false，而不是空 Mono */
    private Mono<Boolean> sameAsStored(String prefix, String userId, String token) {
        if (userId == null) {
            return Mono.just(false);
        }
        return redis.opsForValue().get(prefix + userId).map(token::equals).defaultIfEmpty(false);
    }

    /** 任一路径段（解码后、忽略大小写）是 internal 就算内部接口 */
    static boolean isInternalPath(ServerHttpRequest request) {
        return request.getPath().elements().stream()
                .filter(e -> e instanceof PathContainer.PathSegment)
                .map(e -> ((PathContainer.PathSegment) e).valueToMatch())
                .anyMatch("internal"::equalsIgnoreCase);
    }

    /**
     * 从 token 里取出的身份。规则和下游 TenantContextInterceptor 一致：
     * 商家角色必须有 tenantId；买家 / 平台管理员不能有；SYSTEM 不能出现在用户 token 里（只给服务内部用）
     */
    record Identity(String userId, Role role, String tenantId) {
        static Identity from(Claims claims) {
            String userId = claims.getSubject();
            Role role = Role.parse(claims.get(JwtUtil.CLAIM_ROLE, String.class));
            Object tenantClaim = claims.get(JwtUtil.CLAIM_TENANT_ID);
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
        return -1; // 优先级较高
    }
}
