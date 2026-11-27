package com.merchanthub.order;

import com.merchanthub.order.job.OrderTimeoutScanner;
import com.merchanthub.order.job.OutboxBacklogMonitor;
import com.merchanthub.order.mq.outbox.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression test for the cloud backlog incident: the fast-path pool is saturated, so every post-commit send is dropped -- can the system still heal itself?
 * <p>
 * Setup: fast-path pool of 1 thread + queue of 1, and both slots are taken by a blocked task, so every publishAsync is rejected
 * (exactly what happened in the cloud under overload, just deterministic). Payment timeout 3s, so "order created → timed out → closed"
 * fits in a test. Scan batch 5 so a backlog of 40 rows goes through the catch-up path (full batch → bigger batches in parallel).
 * <p>
 * Conclusions:
 * <ol>
 *   <li>Dropping is safe because the rows were committed with the order: nothing is lost, and the drop is now visible
 *       (counter outbox.fastpath.dropped, WARN log)</li>
 *   <li>The monitor sees "pending for longer than stale-after" &gt; 0 -- in the cloud that number drives the CloudWatch alarm</li>
 *   <li>Once the scan runs, it drains the backlog: ORDER_CREATED and ORDER_TIMEOUT go out, orders close on timeout, ORDER_CLOSED
 *       (dropped again on the fast path) goes out too -- ORDER_CLOSED is what product-inventory consumes to release the reservation
 *       (release itself: OrderSettleConsumerIT; recon catch-up: ReservationReconIT)</li>
 *   <li>Orders whose timeout message was lost (status 2) are still closed by the timeout scan</li>
 *   <li>If nothing triggers the scan at all (scheduler down), the monitor's fallback runs it after 2 rounds without progress</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.pay-timeout=3s",
        "merchanthub.order.timeout-consumer.enabled=true",
        "merchanthub.order.jobs.timeout-grace=1s",
        "merchanthub.order.outbox.fast-path.core-threads=1",
        "merchanthub.order.outbox.fast-path.max-threads=1",
        "merchanthub.order.outbox.fast-path.queue-capacity=1",
        "merchanthub.order.outbox.batch-size=5",
        "merchanthub.order.outbox.catch-up.batch-size=10",
        "merchanthub.order.outbox.monitor.stale-after=2s",
        "merchanthub.order.outbox.monitor.fallback-enabled=true"})
class OutboxSelfHealIT extends OrderItSupport {

    private static OrderEventObserver observer;

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OrderTimeoutScanner timeoutScanner;
    @Autowired
    private OutboxBacklogMonitor monitor;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    @Qualifier("outboxSendExecutor")
    private ExecutorService fastPath;

    private CountDownLatch release;
    private ScheduledExecutorService scheduler;

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    /** Occupy the only thread and the only queue slot, so every further fast-path task is rejected */
    @BeforeEach
    void saturateFastPath() {
        release = new CountDownLatch(1);
        Runnable block = () -> {
            try {
                release.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ThreadPoolExecutor pool = (ThreadPoolExecutor) fastPath;
        pool.execute(block);
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.getActiveCount() == 1);
        pool.execute(block);
        assertThat(pool.getQueue().remainingCapacity()).isZero();
    }

    @AfterEach
    void unblock() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        release.countDown();
    }

    private double dropped() {
        return registry.get("outbox.fastpath.dropped").counter().count();
    }

    private long pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE status = 0", Long.class);
    }

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
    }

    private boolean receivedTag(long orderNo, String tag) {
        return observer.received(orderNo).stream().map(MessageExt::getTags).anyMatch(tag::equals);
    }

    private List<Long> placeOrders(int n) {
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orders.add(Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo()));
        }
        return orders;
    }

    /** Simulates XXL-Job triggering orderOutboxRelayJob and orderTimeoutScanJob */
    private void startJobs() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relay.scan();
                timeoutScanner.scan();
            } catch (RuntimeException ignored) {
                // like the admin: one failed run doesn't stop the next
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    @Test
    void fastPathSaturated_scanDrainsBacklog_ordersCloseAndClosedEventsGoOut() {
        double droppedBefore = dropped();
        List<Long> orders = placeOrders(20);

        // 1. Every fast-path send was dropped -- but visibly, and the rows are all there (2 per order: ORDER_CREATED + ORDER_TIMEOUT)
        assertThat(dropped() - droppedBefore).isGreaterThanOrEqualTo(40);
        assertThat(pending()).isEqualTo(40);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .until(() -> orders.stream().noneMatch(o -> receivedTag(o, "ORDER_CREATED")));

        // 2. Lose the timeout message of 5 orders (as if delivery failed for good): only the timeout scan can close these
        List<Long> lostTimeout = orders.subList(0, 5);
        for (Long o : lostTimeout) {
            jdbc.update("UPDATE order_outbox SET status = 2 WHERE topic = 'ORDER_TIMEOUT' AND biz_key = ?", String.valueOf(o));
        }

        // 3. The monitor flags the backlog once rows are older than stale-after: this is the alarm signal (> 0)
        await().pollDelay(Duration.ofMillis(2100)).atMost(Duration.ofSeconds(5))
                .until(() -> monitor.report().stalePending() == 35);

        // 4. The scheduler comes up: the scan drains everything; orders time out and close; ORDER_CLOSED is dropped on the
        //    fast path again (pool still blocked) and also delivered by the scan
        startJobs();
        await().atMost(Duration.ofSeconds(90)).until(() -> orders.stream().allMatch(o -> receivedTag(o, "ORDER_CREATED")));
        await().atMost(Duration.ofSeconds(90)).until(() -> orders.stream().allMatch(o -> status(o) == 50));
        await().atMost(Duration.ofSeconds(60)).until(() -> orders.stream().allMatch(o -> receivedTag(o, "ORDER_CLOSED")));
        await().atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);

        // 5. Self-healed: nothing stale, no order stuck in pending payment
        OutboxBacklogMonitor.Snapshot s = monitor.report();
        assertThat(s.stalePending()).isZero();
        assertThat(s.expiredPendingOrders()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE tag = 'ORDER_CLOSED' AND status = 1", Long.class))
                .isEqualTo(20);
    }

    /** No scheduler at all (the cloud before XXL-Job was deployed there): the monitor notices the backlog isn't shrinking and runs the scan itself */
    @Test
    void schedulerDown_monitorFallbackDrainsBacklog() {
        List<Long> orders = placeOrders(10);
        assertThat(pending()).isEqualTo(20);

        await().pollDelay(Duration.ofMillis(2100)).atMost(Duration.ofSeconds(5))
                .until(() -> monitor.report().stalePending() == 20);
        // round 1 sets the baseline, rounds 2 and 3 see no progress -> fallback scan on round 3
        monitor.tick();
        monitor.tick();
        assertThat(orders.stream().noneMatch(o -> receivedTag(o, "ORDER_CREATED"))).isTrue();
        monitor.tick();

        await().atMost(Duration.ofSeconds(30)).until(() -> orders.stream().allMatch(o -> receivedTag(o, "ORDER_CREATED")));
        await().atMost(Duration.ofSeconds(10)).until(() -> monitor.report().stalePending() == 0);
    }
}
