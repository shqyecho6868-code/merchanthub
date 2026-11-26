package com.merchanthub.common.tenant.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Only wired in services with Redis (same reason as SnowflakeAutoConfiguration: the check must run after Redis auto-configuration) */
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
