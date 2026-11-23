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
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final StringRedisTemplate stringRedisTemplate;

    public AuthorizeFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
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

        try {
            // 3. Access-Token 有效 → 4. 注入身份头
            if (accessToken != null && !accessToken.isEmpty()) {
                Claims acClaims = JwtUtil.parse(accessToken);
                if (acClaims != null) {
                    String userId = acClaims.getSubject();
                    String redisAccessToken = stringRedisTemplate.opsForValue()
                            .get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
                    if (accessToken.equals(redisAccessToken)) {
                        Identity id = Identity.from(acClaims);
                        if (id == null) {
                            log.warn("token 身份信息不合法 userId={} role={} tenantId={}", userId,
                                    acClaims.get(JwtUtil.CLAIM_ROLE), acClaims.get(JwtUtil.CLAIM_TENANT_ID));
                            return buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_INVALID.getMessage());
                        }
                        cleaned.headers(h -> {
                            h.set(TenantHeaders.USER_ID, id.userId());
                            h.set(TenantHeaders.ROLE, id.role().name());
                            if (id.tenantId() != null) {
                                h.set(TenantHeaders.TENANT_ID, id.tenantId());
                            }
                        });
                        return chain.filter(exchange.mutate().request(cleaned.build()).build());
                    }
                }
            }

            // Access-Token 无效，但 Refresh-Token 有效 → 提示前端去刷新
            if (refreshToken != null && !refreshToken.isEmpty()) {
                Claims rfClaims = JwtUtil.parse(refreshToken);
                if (rfClaims != null) {
                    String userId = rfClaims.getSubject();
                    String redisRefreshToken = stringRedisTemplate.opsForValue()
                            .get(CommonConstant.REFRESH_TOKEN_PREFIX + userId);
                    if (refreshToken.equals(redisRefreshToken)) {
                        return buildErrorResponse(exchange, ErrorCode.TOKEN_EXPIRED);
                    }
                }
            }

            return buildErrorResponse(exchange, ErrorCode.NOT_LOGIN_ERROR);

        } catch (Exception e) {
            log.error("JWT 校验系统异常", e);
            return buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.SYSTEM_ERROR, "登录状态确认失败");
        }
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
