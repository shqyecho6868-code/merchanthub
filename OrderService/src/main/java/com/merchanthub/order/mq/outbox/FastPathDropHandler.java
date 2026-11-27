package com.merchanthub.order.mq.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rejection policy of the fast-path pool: still DiscardPolicy (drop the task, never block or run it on the order thread),
 * but no longer silent -- every drop increments the {@code outbox.fastpath.dropped} counter, and a WARN is logged.
 * <p>
 * Why dropping is acceptable: the outbox row was committed in the order transaction <b>before</b> the task was submitted,
 * so dropping the task loses nothing; the row stays status=0 and the compensation scan sends it later.
 * Why it must be observable: "dropped, the scan will send it" is only true if the scan is actually running.
 * When it wasn't (XXL-Job not deployed in the cloud), 129k rows piled up and nothing in the logs or metrics said so.
 * <p>
 * Under overload drops can happen thousands of times a second, so the WARN is rate-limited to one line per second
 * carrying the number dropped since the last line; the counter itself counts every drop.
 */
@Slf4j
public class FastPathDropHandler implements RejectedExecutionHandler {

    static final String METRIC = "outbox.fastpath.dropped";
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;

    private final Counter dropped;
    private final AtomicLong sinceLastLog = new AtomicLong();
    private final AtomicLong lastLogAt = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    public FastPathDropHandler(MeterRegistry registry) {
        this.dropped = Counter.builder(METRIC)
                .description("Fast-path outbox sends dropped because the send pool was full (rows stay pending for the scan)")
                .register(registry);
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        dropped.increment();
        sinceLastLog.incrementAndGet();
        long now = System.nanoTime();
        long last = lastLogAt.get();
        // Only the thread that wins the CAS logs, so at most one line per second even with many order threads dropping at once
        if (now - last >= LOG_INTERVAL_NANOS && lastLogAt.compareAndSet(last, now)) {
            long n = sinceLastLog.getAndSet(0);
            String id = r instanceof OutboxRelay.FastPathTask t ? String.valueOf(t.outboxId()) : "?";
            log.warn("Outbox fast path saturated, dropped {} send task(s) in the last ~1s (latest outboxId={}, queue={}, active={}). "
                            + "Rows are already committed (status=0) and will be sent by the outbox scan; if this persists, check that the scan job is running",
                    n, id, executor.getQueue().size(), executor.getActiveCount());
        }
    }

    double count() {
        return dropped.count();
    }
}
