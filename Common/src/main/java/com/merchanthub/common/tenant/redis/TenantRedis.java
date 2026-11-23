package com.merchanthub.common.tenant.redis;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 按商家隔离的 Redis 访问入口：业务缓存（商品详情、库存快照等）一律用它，key 自动带 "t:{tenantId}:" 前缀。
 * <p>
 * 全局 key（雪花 workerId 租约、登录 token 这类不属于商家的）继续用 StringRedisTemplate。
 * 不做成 StringRedisTemplate 的子类注册成 Bean：那样按类型注入 StringRedisTemplate 的地方会出现两个候选。
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

    /** 和 StringRedisTemplate 用法一样，只是 key 会自动加租户前缀 */
    public RedisTemplate<String, String> ops() {
        return template;
    }
}
