package com.merchanthub.common.id;

import cn.hutool.core.lang.Snowflake;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 全局唯一 ID 生成器（订单号、支付单号等），替代旧的 SnowflakeUtil / SnowflakeDynamicUtil（pitfalls P1）。
 * <p>
 * 启动时从 Redis 领一个槽位作为 workerId，后台定时续期。
 * 判断“还能不能发号”用的是<b>本地截止时间</b>：每次续期成功前先记下发请求的时刻 t，截止时间 = t + 租约时长。
 * Redis 上的 key 最早也要在这个时刻之后才会过期，别的实例在此之前不可能拿到同一个号，所以本地截止时间是安全的保守估计。
 * 这样 Redis 短暂抖动时不会立刻停服，只有真正超过租约时长都没续上，才停止发号。
 */
@Slf4j
public class SnowflakeIdGenerator implements InitializingBean, DisposableBean {

    private final SnowflakeSlotAllocator allocator;
    private final SnowflakeProperties props;
    /** 单调时钟（纳秒），单测里可替换；不用墙上时间，避免系统时间被调整影响租约判断 */
    private final LongSupplier nanoClock;
    private final String instanceId = UUID.randomUUID().toString();

    private volatile int slot = -1;
    private volatile Snowflake snowflake;
    /** 租约本地截止时间（nanoClock 读数）；超过后 nextId 直接拒绝 */
    private volatile long leaseDeadlineNanos;
    /** 续期时发现号被别人拿走 → 永久失效，只能重启实例重新领号 */
    private volatile boolean leaseLost;
    private ScheduledExecutorService renewer;

    public SnowflakeIdGenerator(SnowflakeSlotAllocator allocator, SnowflakeProperties props) {
        this(allocator, props, System::nanoTime);
    }

    SnowflakeIdGenerator(SnowflakeSlotAllocator allocator, SnowflakeProperties props, LongSupplier nanoClock) {
        this.allocator = allocator;
        this.props = props;
        this.nanoClock = nanoClock;
    }

    @Override
    public void afterPropertiesSet() {
        long requestedAt = nanoClock.getAsLong();
        // Redis 不可用时这里会抛异常，服务启动失败：宁可起不来，也不带着可能重复的号运行
        this.slot = allocator.acquire(instanceId);
        this.leaseDeadlineNanos = requestedAt + TimeUnit.SECONDS.toNanos(props.getLeaseSeconds());
        // 槽位拆成 dataCenterId（高 5 位）+ workerId（低 5 位）
        this.snowflake = new Snowflake(slot % 32, slot / 32);
        log.info("雪花槽位领取成功 slot={} workerId={} dataCenterId={} instanceId={}",
                slot, slot % 32, slot / 32, instanceId);

        renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snowflake-lease-renewer");
            t.setDaemon(true);
            return t;
        });
        renewer.scheduleWithFixedDelay(this::renewOnce,
                props.getRenewIntervalSeconds(), props.getRenewIntervalSeconds(), TimeUnit.SECONDS);
    }

    /** 续期一次；包级可见，方便单测直接调用 */
    void renewOnce() {
        if (leaseLost) {
            return;
        }
        long requestedAt = nanoClock.getAsLong();
        try {
            if (allocator.renew(slot, instanceId)) {
                leaseDeadlineNanos = requestedAt + TimeUnit.SECONDS.toNanos(props.getLeaseSeconds());
            } else {
                leaseLost = true;
                log.error("雪花槽位租约丢失 slot={} instanceId={}，停止发号，需要重启实例重新领号", slot, instanceId);
            }
        } catch (Exception e) {
            // Redis 暂时不可用：不立刻判死，本地截止时间之前仍然安全，下一轮再续
            log.warn("雪花槽位续期失败 slot={}，下一轮重试", slot, e);
        }
    }

    public long nextId() {
        if (leaseLost) {
            throw new IllegalStateException("雪花槽位租约已丢失，拒绝发号 slot=" + slot);
        }
        if (nanoClock.getAsLong() - leaseDeadlineNanos >= 0) {
            throw new IllegalStateException("雪花槽位租约已过期（长时间未续上），拒绝发号 slot=" + slot);
        }
        // 时钟回拨时 hutool 会抛 IllegalStateException，不发号（已知限制，见 B1 笔记）
        return snowflake.nextId();
    }

    public int getSlot() {
        return slot;
    }

    @Override
    public void destroy() {
        if (renewer != null) {
            renewer.shutdownNow();
        }
        // 正常下线时主动归还，号立刻可被复用，不用等租约过期
        if (slot >= 0 && !leaseLost) {
            try {
                allocator.release(slot, instanceId);
            } catch (Exception e) {
                log.warn("雪花槽位归还失败 slot={}，等待租约自然过期", slot, e);
            }
        }
    }
}
