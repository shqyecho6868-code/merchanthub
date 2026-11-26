package com.merchanthub.common.id;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Claims / renews / releases Snowflake slots in Redis (workerId + dataCenterId together form 10 bits, 1024 slots in total).
 * <p>
 * Only talks to Redis and holds no state; whether the lease is still valid is decided by {@link SnowflakeIdGenerator}.
 */
public class SnowflakeSlotAllocator {

    /** hutool Snowflake's workerId and dataCenterId are 5 bits each → 32 × 32 = 1024 slots */
    public static final int MAX_SLOTS = 1024;

    /**
     * Renewal must compare-then-renew atomically:
     * with a separate GET then EXPIRE, the slot could expire in between and be taken by another instance, and we would renew someone else's slot.
     */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** Same for release: only delete our own key, never one another instance has already taken over */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final SnowflakeProperties props;

    public SnowflakeSlotAllocator(StringRedisTemplate redis, SnowflakeProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Try SET NX EX starting from slot 0; the first one that succeeds is ours.
     *
     * @return slot number 0..1023
     * @throws IllegalStateException if all 1024 slots are taken
     */
    public int acquire(String instanceId) {
        Duration lease = Duration.ofSeconds(props.getLeaseSeconds());
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            Boolean ok = redis.opsForValue().setIfAbsent(key(slot), instanceId, lease);
            if (Boolean.TRUE.equals(ok)) {
                return slot;
            }
        }
        throw new IllegalStateException("All Snowflake slots are taken (" + MAX_SLOTS + " in total)");
    }

    /** @return true = renewed; false = this slot is no longer ours (lease lost) */
    public boolean renew(int slot, String instanceId) {
        Long r = redis.execute(RENEW_SCRIPT, List.of(key(slot)),
                instanceId, String.valueOf(props.getLeaseSeconds()));
        return r != null && r == 1L;
    }

    public void release(int slot, String instanceId) {
        redis.execute(RELEASE_SCRIPT, List.of(key(slot)), instanceId);
    }

    String key(int slot) {
        return props.getKeyPrefix() + slot;
    }
}
