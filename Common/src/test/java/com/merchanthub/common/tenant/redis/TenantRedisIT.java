package com.merchanthub.common.tenant.redis;

import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.testsupport.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Redis：两个商家写同一个业务 key 互不覆盖，各自只读得到自己的 */
class TenantRedisIT {

    private static final GenericContainer<?> REDIS = SharedContainers.redis();
    private static LettuceConnectionFactory factory;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @Test
    void sameBizKey_isolatedPerTenant() {
        TenantRedis redis = new TenantRedis(factory);
        String key = "it:product:detail:" + System.nanoTime();
        TenantInfo a = new TenantInfo(7L, 1L, Role.MERCHANT_ADMIN);
        TenantInfo b = new TenantInfo(8L, 2L, Role.MERCHANT_ADMIN);

        TenantContext.runAs(a, () -> redis.ops().opsForValue().set(key, "商家7的数据"));
        TenantContext.runAs(b, () -> redis.ops().opsForValue().set(key, "商家8的数据"));

        assertThat(TenantContext.callAs(a, () -> redis.ops().opsForValue().get(key))).isEqualTo("商家7的数据");
        assertThat(TenantContext.callAs(b, () -> redis.ops().opsForValue().get(key))).isEqualTo("商家8的数据");

        // 底层真实 key 带着前缀
        StringRedisTemplate raw = new StringRedisTemplate(factory);
        raw.afterPropertiesSet();
        assertThat(raw.opsForValue().get("t:7:" + key)).isEqualTo("商家7的数据");
        assertThat(raw.opsForValue().get(key)).isNull();
    }
}
