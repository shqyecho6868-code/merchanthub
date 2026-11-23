package com.merchanthub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import java.security.Key;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 签发与校验（D1：替换旧的静态 JwtUtil）。
 * <p>
 * 和旧代码的两处区别：
 * <ol>
 *   <li><b>密钥来自配置</b>（{@code merchanthub.jwt.secret}），不再写死在 CommonConstant：
 *       写死在代码里 = 所有能看到仓库的人都能签发任意身份的 token；而且开发 / 生产无法用不同密钥</li>
 *   <li><b>只接受补齐的标准 Base64</b>：旧密钥 62 个字符、没补 "="，jjwt 的宽松解码器会多解出一个字节，
 *       openssl / base64 命令会丢掉那个字节，两边算出的签名对不上（P26）。这里长度不是 4 的倍数直接启动失败</li>
 * </ol>
 * 网关只调 {@link #parse}，只有 user-tenant 调 {@link #issue}。
 */
@Slf4j
public final class JwtCodec {

    /** JWT 里的身份字段名（C1）。网关解析后转成 X-Tenant-Id / X-Role 请求头 */
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";

    /** HS256 的密钥至少 256 位，短了 jjwt 会拒绝；这里提前给出看得懂的报错 */
    static final int MIN_KEY_BYTES = 32;

    private final Key key;

    public JwtCodec(String base64Secret) {
        this.key = Keys.hmacShaKeyFor(decodeStrict(base64Secret));
    }

    static byte[] decodeStrict(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("未配置 merchanthub.jwt.secret（本地放在 .env 的 JWT_SECRET）");
        }
        String s = secret.trim();
        if (s.length() % 4 != 0) {
            throw new IllegalArgumentException("merchanthub.jwt.secret 必须是补齐的标准 Base64（长度是 4 的倍数，末尾用 = 补齐），"
                    + "当前长度 " + s.length() + "。生成：openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("merchanthub.jwt.secret 不是合法的标准 Base64", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("merchanthub.jwt.secret 解码后只有 " + bytes.length + " 字节，HS256 至少 32 字节");
        }
        return bytes;
    }

    /**
     * 签发带身份的 token：subject = userId，另带 role，商家角色再带 tenantId。
     * 身份放进签名过的 token 里，客户端改不了；网关只信 token，不信客户端自己带的请求头。
     *
     * @param tenantId 商家角色必填；买家 / 平台管理员传 null
     */
    public String issue(long userId, Long tenantId, String role, Duration ttl) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256);
        if (tenantId != null) {
            builder.claim(CLAIM_TENANT_ID, String.valueOf(tenantId));
        }
        return builder.compact();
    }

    /** 签名对、没过期 → claims；否则 null（调用方只需要"能不能信"，不需要区分原因，原因记在日志里） */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期：{}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 签名无效或格式错误：{}", e.getMessage());
            return null;
        }
    }
}
