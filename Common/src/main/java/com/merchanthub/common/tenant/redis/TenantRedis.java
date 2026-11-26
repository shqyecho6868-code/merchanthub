package com.merchanthub.common.tenant.redis;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Merchant-isolated Redis entry point: all business caches (product details, inventory snapshots, etc.) go through it, with keys automatically prefixed "t:{tenantId}:".
 * <p>
 * Global keys (Snowflake workerId leases, login tokens and other non-merchant data) keep using StringRedisTemplate.
 * Not registered as a StringRedisTemplate subclass Bean: that would give two candidates wherever StringRedisTemplate is injected by type.
 */
public class TenantRedis {

    private final RedisTemplate<String, String> template;

    public TenantRedis(RedisConnectionFactory factory) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        t.setKeySerializer(new TenantKeySerializer());
        t.setHashKeySerializer(StringRedisSerializer.UTF_8);
        t.setValueSerializer(StringRedisSerializer.UTF_8);
        t.setHashValueSerializer(StringRedisSerializer.UTF_8);
        t.afterPropertiesSet();
        this.template = t;
    }

    /** Used just like StringRedisTemplate, except keys get the tenant prefix automatically */
    public RedisTemplate<String, String> ops() {
        return template;
    }
}
