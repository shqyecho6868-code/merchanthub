package com.merchanthub.common.tenant.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 有 Redis 的服务才装配（同 SnowflakeAutoConfiguration 的理由：要等 Redis 自动装配之后再判断） */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnBean(RedisConnectionFactory.class)
public class TenantRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantRedis tenantRedis(RedisConnectionFactory factory) {
        return new TenantRedis(factory);
    }
}
