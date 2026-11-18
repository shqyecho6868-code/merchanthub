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
 * 雪花 ID 生成器自动装配。
 * <p>
 * 为什么用 AutoConfiguration 而不是像 MybatisPlusConfig 那样靠 scanBasePackages 扫描：
 * 这里要用 @ConditionalOnBean(StringRedisTemplate) 判断“服务里有没有 Redis”，
 * 而组件扫描到的配置类会先于 Redis 自动装配处理，那时 StringRedisTemplate 还没注册，条件永远不成立。
 * 自动装配类可以用 after 指定顺序；@SpringBootApplication 的扫描也会自动排除它，不会重复加载。
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
        // 旧 IM 代码用静态方法取 ID，这里把 Bean 挂到静态门面上；新代码请直接注入 SnowflakeIdGenerator
        SnowflakeUtil.bind(generator);
        return generator;
    }
}
