package com.merchanthub.common.tenant.redis;

import com.merchanthub.common.tenant.TenantContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Automatically adds the tenant prefix to Redis keys (C1): "cart:sku:101" → "t:7:cart:sku:101". Strips the prefix when keys are read back (e.g. scan / keys).
 * <p>
 * Why in the serializer instead of each caller building keys: key building is string concatenation scattered everywhere, and missing one spot leaks across tenants;
 * the serializer sits on every command's path, so with {@link TenantRedis} it's impossible to forget.
 * Fails immediately when there is no merchant context (same fail-closed rule as SQL).
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
