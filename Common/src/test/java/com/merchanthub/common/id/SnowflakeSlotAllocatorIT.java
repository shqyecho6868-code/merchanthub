package com.merchanthub.common.id;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.merchanthub.testsupport.SharedContainers;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 用真实 Redis 验证领号的三个关键行为：多实例错开、租约过期回收、不会误续/误删别人的号。
 */
class SnowflakeSlotAllocatorIT {

    private static final GenericContainer<?> REDIS = SharedContainers.redis();

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final SnowflakeProperties props = new SnowflakeProperties();
    private SnowflakeSlotAllocator allocator;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        allocator = new SnowflakeSlotAllocator(redis, props);
    }

    @Test
    void twoInstances_getDifferentSlots() {
        assertThat(allocator.acquire("instance-A")).isEqualTo(0);
        assertThat(allocator.acquire("instance-B")).isEqualTo(1);
    }

    @Test
    void crashedInstance_slotIsReclaimedAfterLeaseExpires() {
        props.setLeaseSeconds(1);
        assertThat(allocator.acquire("instance-A")).isEqualTo(0);
        // A 宕机，不再续期；租约到期后 0 号应能被 B 拿到
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(allocator.key(0))));
        assertThat(allocator.acquire("instance-B")).isEqualTo(0);
    }

    @Test
    void renew_onlyWhenSlotStillMine() {
        int slot = allocator.acquire("instance-A");
        assertThat(allocator.renew(slot, "instance-A")).isTrue();

        // 模拟 A 的租约过期、号被 B 接手
        redis.opsForValue().set(allocator.key(slot), "instance-B");
        assertThat(allocator.renew(slot, "instance-A")).isFalse();

        // A 下线时也不能删掉 B 的号
        allocator.release(slot, "instance-A");
        assertThat(redis.opsForValue().get(allocator.key(slot))).isEqualTo("instance-B");
    }

    @Test
    void release_makesSlotImmediatelyReusable() {
        int slot = allocator.acquire("instance-A");
        allocator.release(slot, "instance-A");
        assertThat(allocator.acquire("instance-B")).isEqualTo(slot);
    }
}
