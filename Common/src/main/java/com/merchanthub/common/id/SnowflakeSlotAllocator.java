package com.merchanthub.common.id;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 在 Redis 上领取 / 续期 / 归还雪花槽位（workerId + dataCenterId 合起来的 10 位，共 1024 个）。
 * <p>
 * 只负责和 Redis 打交道，不持有状态；“租约是否还有效”由 {@link SnowflakeIdGenerator} 判断。
 */
public class SnowflakeSlotAllocator {

    /** hutool Snowflake 的 workerId、dataCenterId 各 5 位 → 32 × 32 = 1024 个槽位 */
    public static final int MAX_SLOTS = 1024;

    /**
     * 续期必须“先比对再续”，两步要原子：
     * 如果先 GET 再 EXPIRE，中间号可能刚好过期并被别的实例拿走，就会把别人的号续上。
     */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** 归还同理：只删自己的，不能误删别人已经接手的号 */
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
     * 从 0 号开始依次尝试 SET NX EX，第一个成功的就是自己的号。
     *
     * @return 槽位号 0..1023
     * @throws IllegalStateException 1024 个号全被占用
     */
    public int acquire(String instanceId) {
        Duration lease = Duration.ofSeconds(props.getLeaseSeconds());
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            Boolean ok = redis.opsForValue().setIfAbsent(key(slot), instanceId, lease);
            if (Boolean.TRUE.equals(ok)) {
                return slot;
            }
        }
        throw new IllegalStateException("雪花槽位已全部被占用（" + MAX_SLOTS + " 个）");
    }

    /** @return true = 续期成功；false = 这个号已经不是自己的了（租约丢失） */
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
