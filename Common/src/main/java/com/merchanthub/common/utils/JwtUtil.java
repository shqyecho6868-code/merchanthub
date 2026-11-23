package com.merchanthub.common.utils;


import com.merchanthub.common.constant.CommonConstant;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.security.Key;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT工具类（优化点：完善异常日志、统一过期时间配置）
 */
@Slf4j
public final class JwtUtil {

    /**
     * 生成JWT（支持自定义过期时间）
     */
    public static String generate(String userId, long timeout, TimeUnit unit) {
        Date now = new Date();
        //设置过期时间
        Date expiration = new Date(now.getTime() + unit.toMillis(timeout));
        System.out.println(expiration);
        return Jwts.builder()
                .setSubject(userId) // 存储用户唯一标识（如手机号/用户ID）
                .setIssuedAt(now) // 签发时间
                .setExpiration(expiration) // JWT自身过期时间长一点
                .signWith(getSignInKey(), SignatureAlgorithm.HS256) // 签名算法
                .compact();
    }

    /** JWT 里的身份字段名（C1）。网关解析后转成 X-Tenant-Id / X-Role 请求头 */
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";

    /**
     * 生成带身份的 JWT（C1）：subject = userId，另带 role，商家角色再带 tenantId。
     * 身份放进签名过的 token 里，客户端改不了；网关只信 token，不信客户端自己带的请求头。
     *
     * @param tenantId 商家角色必填；买家 / 平台管理员传 null
     */
    public static String generate(long userId, Long tenantId, String role, long timeout, TimeUnit unit) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + unit.toMillis(timeout)))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256);
        if (tenantId != null) {
            builder.claim(CLAIM_TENANT_ID, String.valueOf(tenantId));
        }
        return builder.compact();
    }

    /**
     * 获取签名密钥（从常量类读取，避免硬编码）
     */
    public static Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(CommonConstant.TOKEN_SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 解析JWT（区分不同异常类型，便于排查）
     */
    public static Claims parse(String token) {
        if (StringUtils.isEmpty(token)) {
            log.warn("解析JWT失败：token 为空");
            return null;
        }
        try {
            return (Claims) Jwts.parserBuilder()
                    .setSigningKey(getSignInKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期：{}", e.getMessage());
            return null;
        } catch (JwtException e) {
            log.error("JWT 签名无效或格式错误：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("JWT 解析发生未知错误：", e);
            return null;
        }
    }


}
