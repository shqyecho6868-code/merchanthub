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
 * Globally unique ID generator (order numbers, payment numbers, etc.), replacing the old SnowflakeUtil / SnowflakeDynamicUtil (pitfalls P1).
 * <p>
 * On startup it claims a slot in Redis as its workerId and renews it periodically in the background.
 * Whether it may still issue IDs is decided by a <b>local deadline</b>: before each renewal we record the send time t; on success, deadline = t + lease duration.
 * The Redis key cannot expire before that moment, so no other instance can grab the same slot earlier; the local deadline is a safe, conservative estimate.
 * This way a brief Redis hiccup doesn't stop the service; ID issuing stops only if renewal fails for longer than the whole lease.
 */
@Slf4j
public class SnowflakeIdGenerator implements InitializingBean, DisposableBean {

    private final SnowflakeSlotAllocator allocator;
    private final SnowflakeProperties props;
    /** Monotonic clock (nanoseconds), replaceable in unit tests; not wall-clock time, so system clock adjustments can't affect the lease check */
    private final LongSupplier nanoClock;
    private final String instanceId = UUID.randomUUID().toString();

    private volatile int slot = -1;
    private volatile Snowflake snowflake;
    /** Local lease deadline (a nanoClock reading); past it, nextId refuses outright */
    private volatile long leaseDeadlineNanos;
    /** Renewal found the slot taken by someone else → permanently invalid; only a restart can claim a new slot */
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
        // If Redis is unavailable this throws and startup fails: better not to start than to run with a possibly duplicate slot
        this.slot = allocator.acquire(instanceId);
        this.leaseDeadlineNanos = requestedAt + TimeUnit.SECONDS.toNanos(props.getLeaseSeconds());
        // Split the slot into dataCenterId (high 5 bits) + workerId (low 5 bits)
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

    /** Renew once; package-private so unit tests can call it directly */
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
            // Redis temporarily unavailable: don't give up yet, we're still safe until the local deadline; retry next round
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
        // On clock rollback hutool throws IllegalStateException and no ID is issued (known limitation, see B1 notes)
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
        // Release the slot on graceful shutdown so it can be reused immediately instead of waiting for the lease to expire
        if (slot >= 0 && !leaseLost) {
            try {
                allocator.release(slot, instanceId);
            } catch (Exception e) {
                log.warn("雪花槽位归还失败 slot={}，等待租约自然过期", slot, e);
            }
        }
    }
}
