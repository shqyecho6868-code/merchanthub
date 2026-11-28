package com.merchanthub.order.job;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mq.outbox.OutboxRelay;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches whether the outbox safety net is actually working ("eventually consistent" needs someone checking that "eventually" arrives).
 * <p>
 * Deliberately scheduled by Spring {@code @Scheduled} inside order, <b>not</b> by XXL-Job: the thing being watched is "is the XXL-Job scan running",
 * so the watcher must not depend on XXL-Job. If XXL-Job is missing / down, this still reports, and the CloudWatch alarm still fires.
 * Runs on every order instance; it only reads counts (and the fallback scan below is safe to run concurrently thanks to claim).
 * <p>
 * Each round:
 * <ol>
 *   <li>Counts pending outbox rows older than {@code stale-after} (5m) and orders still pending past their deadline + grace;
 *       exposes them as Micrometer gauges and pushes them to the {@link BacklogMetricsSink} (CloudWatch in the cloud)</li>
 *   <li>Fallback: if the stale count is &gt; 0 and has not gone down for {@code fallback-after-rounds} consecutive rounds, nobody is draining --
 *       logs an ERROR and runs {@link OutboxRelay#scan()} itself. With a healthy scheduler the count goes down and this never triggers</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "merchanthub.order.outbox.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogMonitor {

    private final OrderOutboxMapper outboxMapper;
    private final OrdersMapper ordersMapper;
    private final OutboxRelay relay;
    private final ObjectProvider<BacklogMetricsSink> sinks;
    private final Clock clock;

    private final AtomicLong stalePending = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong expiredPendingOrders = new AtomicLong();
    private final MeterRegistry registry;
    private double lastDropped;
    private long lastStale = -1;
    private int noProgressRounds;
    private boolean fallbackRanLastRound;

    @Value("${merchanthub.order.outbox.monitor.stale-after:5m}")
    private Duration staleAfter = Duration.ofMinutes(5);
    @Value("${merchanthub.order.jobs.timeout-grace:5m}")
    private Duration timeoutGrace = Duration.ofMinutes(5);
    @Value("${merchanthub.order.outbox.monitor.fallback-enabled:true}")
    private boolean fallbackEnabled = true;
    @Value("${merchanthub.order.outbox.monitor.fallback-after-rounds:2}")
    private int fallbackAfterRounds = 2;

    public OutboxBacklogMonitor(OrderOutboxMapper outboxMapper, OrdersMapper ordersMapper, OutboxRelay relay,
                                ObjectProvider<BacklogMetricsSink> sinks, MeterRegistry registry, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.ordersMapper = ordersMapper;
        this.relay = relay;
        this.sinks = sinks;
        this.registry = registry;
        this.clock = clock;
        Gauge.builder("outbox.pending.stale", stalePending, AtomicLong::get)
                .description("Pending outbox rows older than stale-after (should be 0 when the scan works)").register(registry);
        Gauge.builder("outbox.pending", pending, AtomicLong::get)
                .description("All pending outbox rows").register(registry);
        Gauge.builder("orders.expired.pending", expiredPendingOrders, AtomicLong::get)
                .description("Orders still pending payment after deadline + grace (timeout close missing)").register(registry);
    }

    public record Snapshot(long stalePending, long pending, long expiredPendingOrders, long fastPathDropped) {
    }

    @Scheduled(fixedDelayString = "${merchanthub.order.outbox.monitor.interval-ms:60000}",
            initialDelayString = "${merchanthub.order.outbox.monitor.initial-delay-ms:30000}")
    public void tick() {
        try {
            Snapshot s = report();
            maybeFallback(s.stalePending());
        } catch (RuntimeException e) {
            // A failed round (DB blip) must not kill the schedule; the alarm treats missing data as breaching anyway
            log.warn("Outbox backlog monitor round failed", e);
        }
    }

    /** Counts, updates gauges and pushes to the sink. Public so tests can drive it without waiting for the schedule */
    public Snapshot report() {
        LocalDateTime now = LocalDateTime.now(clock);
        long stale = TenantContext.callAsSystem(() -> outboxMapper.countPendingCreatedBefore(now.minus(staleAfter)));
        long all = TenantContext.callAsSystem(outboxMapper::countPending);
        long expired = TenantContext.callAsSystem(() -> ordersMapper.countExpiredPending(now.minus(timeoutGrace)));
        stalePending.set(stale);
        pending.set(all);
        expiredPendingOrders.set(expired);
        Snapshot s = new Snapshot(stale, all, expired, droppedSinceLastReport());
        if (stale > 0 || expired > 0) {
            log.warn("Outbox backlog: stalePending={} (older than {}), pending={}, expiredPendingOrders={}, fastPathDropped(last round)={}",
                    stale, staleAfter, all, expired, s.fastPathDropped());
        }
        sinks.orderedStream().forEach(sink -> {
            try {
                sink.publish(s);
            } catch (RuntimeException e) {
                log.warn("Publishing outbox backlog metrics failed sink={}", sink.getClass().getSimpleName(), e);
            }
        });
        return s;
    }

    private long droppedSinceLastReport() {
        var c = registry.find("outbox.fastpath.dropped").counter();
        double total = c == null ? 0 : c.count();
        long delta = (long) (total - lastDropped);
        lastDropped = total;
        return delta;
    }

    /** Second line of defense for the scheduler itself: only kicks in when the backlog is not shrinking */
    void maybeFallback(long stale) {
        if (stale > 0 && lastStale >= 0 && stale >= lastStale) {
            noProgressRounds++;
        } else {
            noProgressRounds = 0;
        }
        lastStale = stale;
        // One fallback scan is time-boxed (catch-up budget), so a big backlog needs several. If the last round had to fall back and
        // rows are still stale, the scheduler is still down: keep going every round instead of waiting another N flat rounds
        // (in the cloud drill that wait left 4.6k rows stale for 3 more minutes)
        boolean continuing = fallbackRanLastRound && stale > 0;
        fallbackRanLastRound = false;
        if (!fallbackEnabled || !(continuing || noProgressRounds >= fallbackAfterRounds)) {
            return;
        }
        if (continuing) {
            log.warn("Outbox backlog still stale after the previous fallback scan (stalePending={}): continuing the fallback", stale);
        } else {
            log.error("Outbox backlog not shrinking for {} rounds (stalePending={}): the scheduled scan looks down (is XXL-Job running?). "
                    + "Running the scan from the monitor as a fallback", noProgressRounds, stale);
        }
        fallbackRanLastRound = true;
        int n = relay.scan();
        log.warn("Outbox monitor fallback scan sent {} rows", n);
    }
}
