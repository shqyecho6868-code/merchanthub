package com.merchanthub.common.utils;

import com.merchanthub.common.id.SnowflakeIdGenerator;

/**
 * 静态门面：只为兼容旧 IM 代码里的 {@code SnowflakeUtil.nextId()} 调用。
 * <p>
 * 原实现把 workerId 写死为 1，多实例会撞号（pitfalls P1）；现在委托给 Redis 领号的 {@link SnowflakeIdGenerator}。
 * 新代码（订单号、支付单号）直接注入 SnowflakeIdGenerator，不要用这个类。旧 IM 代码删除后这个类一起删。
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
            // 没配 Redis 的服务没有生成器；宁可报错，也不退回写死 workerId 的老做法
            throw new IllegalStateException("SnowflakeIdGenerator 未初始化：当前服务需要依赖 Redis");
        }
        return g.nextId();
    }
}
