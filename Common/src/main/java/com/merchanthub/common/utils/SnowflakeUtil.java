package com.merchanthub.common.utils;

import com.merchanthub.common.id.SnowflakeIdGenerator;

/**
 * Static facade: exists only for {@code SnowflakeUtil.nextId()} calls in the old IM code.
 * <p>
 * The original implementation hard-coded workerId to 1, so multiple instances collided (pitfalls P1); it now delegates to the Redis-leased {@link SnowflakeIdGenerator}.
 * New code (order numbers, payment numbers) should inject SnowflakeIdGenerator directly, not use this class. It goes away together with the old IM code.
 */
public final class SnowflakeUtil {

    private static volatile SnowflakeIdGenerator generator;

    private SnowflakeUtil() {
    }

    public static void bind(SnowflakeIdGenerator g) {
        generator = g;
    }

    public static long nextId() {
        SnowflakeIdGenerator g = generator;
        if (g == null) {
            // Services without Redis have no generator; better to fail than fall back to the old hard-coded workerId
            throw new IllegalStateException("SnowflakeIdGenerator 未初始化：当前服务需要依赖 Redis");
        }
        return g.nextId();
    }
}
