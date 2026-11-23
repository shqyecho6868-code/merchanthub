package com.merchanthub.common.tenant;

import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * 租户上下文：用 ThreadLocal 存"当前线程代表谁"（C1）。
 * <p>
 * 写入点：Web 拦截器（请求头）、MQ 消费（消息属性）、定时任务（显式 {@link #runAsSystem}）。
 * 读取点：MyBatis-Plus 租户插件拼 SQL、Feign 拦截器透传请求头、Redis key 前缀、RBAC 校验。
 * <p>
 * <b>为什么必须清理</b>：Tomcat、RocketMQ 消费、线程池的线程都是复用的。商家 7 的请求用完线程不清理，
 * 下一个分到这个线程的请求（可能是商家 8 的、也可能根本没带身份）就会带着"我是商家 7"去查库 ——
 * 串租户，而且不会有任何报错。所以只提供 {@link #open} 这种"用完自动还原"的写法，不提供裸 set。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 当前身份；没有返回 null */
    public static TenantInfo get() {
        return HOLDER.get();
    }

    /** 当前商家 ID；没有上下文或者不是按商家执行时返回 null */
    public static Long tenantId() {
        TenantInfo info = HOLDER.get();
        return info == null ? null : info.tenantId();
    }

    /** 必须有商家：用在"只有商家才能做"的地方（如 Redis 租户 key），拿不到就报错，不猜 */
    public static long requireTenantId() {
        Long id = tenantId();
        if (id == null) {
            throw new TenantContextMissingException("当前线程没有商家上下文");
        }
        return id;
    }

    /**
     * 切换到 info，返回的 Scope 关闭时<b>还原成切换前的样子</b>（之前没有就 remove）。
     * 用还原而不是直接 remove：系统任务里嵌套"按某个商家处理"时，处理完要回到系统身份，而不是变成"什么都没有"。
     */
    public static Scope open(TenantInfo info) {
        TenantInfo previous = HOLDER.get();
        HOLDER.set(info);
        putMdc(info);
        return new Scope(previous);
    }

    public static void runAs(TenantInfo info, Runnable action) {
        try (Scope ignored = open(info)) {
            action.run();
        }
    }

    public static <T> T callAs(TenantInfo info, Supplier<T> action) {
        try (Scope ignored = open(info)) {
            return action.get();
        }
    }

    /** 以跨租户的系统身份执行：只给扫描任务、渠道回调这类本来就不属于某个商家的入口用 */
    public static void runAsSystem(Runnable action) {
        runAs(TenantInfo.system(), action);
    }

    public static <T> T callAsSystem(Supplier<T> action) {
        return callAs(TenantInfo.system(), action);
    }

    private static void putMdc(TenantInfo info) {
        // 日志带 tenantId（CLAUDE.md 第 6 节）：排查"谁的请求"时直接按 tenantId 搜日志
        if (info == null || info.tenantId() == null) {
            MDC.remove("tenantId");
        } else {
            MDC.put("tenantId", String.valueOf(info.tenantId()));
        }
    }

    /** try-with-resources 用：close 时还原 */
    public static final class Scope implements AutoCloseable {
        private final TenantInfo previous;
        private boolean closed;

        private Scope(TenantInfo previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                HOLDER.remove();   // remove 而不是 set(null)：彻底从线程的 ThreadLocalMap 里删掉这一项
            } else {
                HOLDER.set(previous);
            }
            putMdc(previous);
        }
    }
}
