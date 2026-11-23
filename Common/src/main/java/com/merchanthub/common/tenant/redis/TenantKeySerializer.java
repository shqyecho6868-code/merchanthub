package com.merchanthub.common.tenant.redis;

import com.merchanthub.common.tenant.TenantContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Redis key 自动加租户前缀（C1）："cart:sku:101" → "t:7:cart:sku:101"。读回 key（如 scan / keys）时去掉前缀。
 * <p>
 * 为什么做在序列化器里而不是让每个调用方自己拼：拼 key 是散落在各处的字符串操作，漏一处就串租户；
 * 序列化器是所有命令的必经之路，用了 {@link TenantRedis} 就不可能忘。
 * 没有商家上下文时直接报错（同 SQL 的 fail-closed）。
 */
public class TenantKeySerializer implements RedisSerializer<String> {

    static final String PREFIX = "t:";

    public static String prefixed(long tenantId, String key) {
        return PREFIX + tenantId + ":" + key;
    }

    @Override
    public byte[] serialize(String key) {
        if (key == null) {
            return null;
        }
        return prefixed(TenantContext.requireTenantId(), key).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String prefix = prefixed(TenantContext.requireTenantId(), "");
        return raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw;
    }
}
