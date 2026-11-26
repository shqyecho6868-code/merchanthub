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
 * Verifies three key behaviors of slot claiming against real Redis: instances get different slots, expired leases are reclaimed, and nobody renews/deletes someone else's slot by mistake.
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
        // A crashes and stops renewing; once the lease expires, B should be able to take slot 0
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(allocator.key(0))));
        assertThat(allocator.acquire("instance-B")).isEqualTo(0);
    }

    @Test
    void renew_onlyWhenSlotStillMine() {
        int slot = allocator.acquire("instance-A");
        assertThat(allocator.renew(slot, "instance-A")).isTrue();

        // Simulate A's lease expiring and B taking over the slot
        redis.opsForValue().set(allocator.key(slot), "instance-B");
        assertThat(allocator.renew(slot, "instance-A")).isFalse();

        // When A shuts down it must not delete B's slot either
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
