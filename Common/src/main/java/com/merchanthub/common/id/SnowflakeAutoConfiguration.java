package com.merchanthub.common.id;

import com.merchanthub.common.utils.SnowflakeUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration for the Snowflake ID generator.
 * <p>
 * Why an AutoConfiguration instead of relying on scanBasePackages like MybatisPlusConfig:
 * we need @ConditionalOnBean(StringRedisTemplate) to check "does this service have Redis",
 * but component-scanned config classes are processed before Redis auto-configuration, when StringRedisTemplate isn't registered yet, so the condition would never match.
 * Auto-configurations can declare ordering via after; @SpringBootApplication scanning also excludes them, so it isn't loaded twice.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeSlotAllocator snowflakeSlotAllocator(StringRedisTemplate redis, SnowflakeProperties props) {
        return new SnowflakeSlotAllocator(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeSlotAllocator allocator, SnowflakeProperties props) {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(allocator, props);
        // Old IM code gets IDs via a static method, so hook the Bean onto the static facade; new code should inject SnowflakeIdGenerator directly
        SnowflakeUtil.bind(generator);
        return generator;
    }
}
