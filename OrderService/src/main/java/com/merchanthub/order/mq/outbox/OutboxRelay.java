package com.merchanthub.order.mq.outbox;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.common.trace.TraceContext;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import com.merchanthub.order.mq.OrderEventTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Delivery and compensation for the local message table: sends order_outbox records with status = 0 to MQ.
 * <p>
 * Two entry points share the same {@link #publish}:
 * <ul>
 *   <li>{@link #publishAsync}: sent once right after the order transaction commits (fast path)</li>
 *   <li>{@link #scan}: periodically re-sends due records (safety net: MQ unavailable, process died before sending, fast-path task dropped).
 *       Scheduled by XXL-Job ({@code orderOutboxRelayJob}); {@code OutboxBacklogMonitor} watches the backlog independently and
 *       runs the scan itself if the backlog stops shrinking (the scheduler may be down)</li>
 * </ul>
 * When several instances scan at once, the conditional update in {@link OrderOutboxMapper#claim} ensures only one instance sends a given record at a time.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OrderOutboxMapper mapper;
    private final DefaultMQProducer producer;
    private final ExecutorService executor;
    private final ExecutorService catchUpExecutor;
    private final Clock clock;

    @Value("${merchanthub.order.outbox.batch-size:100}")
    private int batchSize;
    @Value("${merchanthub.order.outbox.lease-seconds:30}")
    private long leaseSeconds;
    @Value("${merchanthub.order.outbox.base-backoff-seconds:10}")
    private long baseBackoffSeconds;
    @Value("${merchanthub.order.outbox.max-backoff-seconds:600}")
    private long maxBackoffSeconds;
    @Value("${merchanthub.order.outbox.max-retries:10}")
    private int maxRetries;
    @Value("${merchanthub.order.outbox.catch-up.batch-size:1000}")
    private int catchUpBatchSize = 1000;
    /** One scan call keeps draining for at most this long; must stay below the XXL-Job executor timeout of orderOutboxRelayJob (60s) */
    @Value("${merchanthub.order.outbox.catch-up.time-budget:45s}")
    private Duration catchUpBudget = Duration.ofSeconds(45);

    // In TX_MESSAGE mode there is also a transactional producer (TransactionMQProducer extends DefaultMQProducer), so select the plain producer by name
    @Autowired
    public OutboxRelay(OrderOutboxMapper mapper, @Qualifier("orderOutboxProducer") DefaultMQProducer producer,
                       @Qualifier("outboxSendExecutor") ExecutorService executor,
                       @Qualifier("outboxCatchUpExecutor") ExecutorService catchUpExecutor, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.catchUpExecutor = catchUpExecutor;
        this.clock = clock;
    }

    /** Unit tests: no catch-up pool, backlog batches are sent sequentially on the calling thread */
    OutboxRelay(OrderOutboxMapper mapper, DefaultMQProducer producer, ExecutorService executor, Clock clock) {
        this(mapper, producer, executor, null, clock);
    }

    /**
     * Fast path: send asynchronously right after commit, without holding the order request thread.
     * Pool threads do <b>not</b> have the committing thread's tenant context (ThreadLocal doesn't cross threads), so use SYSTEM explicitly:
     * the outbox is infrastructure and processes any merchant's records by id; which merchant a message belongs to comes from the outbox row's tenant_id (see toMessage).
     * If the pool is full the task is dropped by {@link FastPathDropHandler} (counted + WARN); the row is already committed and the scan sends it.
     */
    public void publishAsync(long outboxId) {
        executor.execute(new FastPathTask(outboxId, () -> TenantContext.runAsSystem(() -> {
            OrderOutbox row = mapper.selectById(outboxId);
            if (row != null && row.getStatus() == OrderOutbox.PENDING) {
                publish(row);
            }
        })));
    }

    /** A fast-path task that remembers which outbox row it was for, so a drop can be logged with the id */
    record FastPathTask(long outboxId, Runnable body) implements Runnable {
        @Override
        public void run() {
            body.run();
        }
    }

    /**
     * Safety net: scan due pending records. Triggered by XXL-Job (the admin guarantees only one instance is invoked at a time);
     * even triggering on two instances at once is safe: the conditional update in claim lets only one instance send each record.
     * <p>
     * Catch-up: one batch of 100 every 5s is only 20 rows/s, so a 129k backlog would need ~1.8 hours. When a batch comes back full
     * (= there is probably more), the scan switches to catch-up mode: bigger batches, sent in parallel on {@code outboxCatchUpExecutor},
     * looping until a batch is no longer full or the time budget is used up. The loop always ends: every row it touches either becomes
     * sent, or gets its next_retry_at pushed into the future (lease / backoff), so the next SELECT returns different rows.
     *
     * @return number of due records found (and attempted) in this call
     */
    public int scan() {
        // The job thread has no context; the scan spans all merchants anyway, so declare SYSTEM explicitly (otherwise the tenant plugin throws)
        return TenantContext.callAsSystem(this::drain);
    }

    private int drain() {
        long deadline = System.nanoTime() + catchUpBudget.toNanos();
        int limit = batchSize;
        int total = 0;
        int rounds = 0;
        long started = System.nanoTime();
        while (true) {
            List<OrderOutbox> due = mapper.selectDue(LocalDateTime.now(clock), limit);
            if (due.isEmpty()) {
                break;
            }
            boolean backlog = due.size() >= limit;
            if (backlog || rounds > 0) {
                sendParallel(due);
            } else {
                due.forEach(this::publish);   // normal case: a few rows, send on this thread; each row caught separately
            }
            total += due.size();
            rounds++;
            if (!backlog || System.nanoTime() >= deadline) {
                break;
            }
            limit = Math.max(batchSize, catchUpBatchSize);
        }
        if (rounds > 1) {
            long ms = Math.max(1, (System.nanoTime() - started) / 1_000_000);
            log.info("Outbox scan catch-up: {} rows in {} rounds, {} ms ({} rows/s)", total, rounds, ms, total * 1000L / ms);
        } else if (total > 0) {
            log.info("Outbox scan: {} due records sent", total);
        }
        return total;
    }

    /** Split a batch into slices and send them on the catch-up pool; waits for all slices so the next SELECT doesn't race them */
    private void sendParallel(List<OrderOutbox> rows) {
        if (catchUpExecutor == null) {
            rows.forEach(this::publish);
            return;
        }
        int slices = 8;
        int size = (rows.size() + slices - 1) / slices;
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += size) {
            List<OrderOutbox> slice = rows.subList(i, Math.min(rows.size(), i + size));
            // Pool threads have no tenant context; run as SYSTEM like the scan thread
            tasks.add(() -> {
                TenantContext.runAsSystem(() -> slice.forEach(this::publish));
                return null;
            });
        }
        try {
            for (Future<Void> f : catchUpExecutor.invokeAll(tasks)) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // publish catches its own exceptions; anything here is unexpected (e.g. DB down). Rows not sent stay pending for the next scan
            log.warn("Outbox catch-up slice failed, remaining rows will be retried by the next scan", e.getCause());
        }
    }

    public void publish(OrderOutbox row) {
        // Open each record's own traceId (from the request that wrote it) and restore afterwards:
        // delivery logs link back to the order request, and stamp writes it into message properties; when scanning a batch it switches per record so it never leaks into the next (old rows without trace_id get a new one)
        try (TraceContext.Scope ignored = TraceContext.open(row.getTraceId())) {
            doPublish(row);
        }
    }

    private void doPublish(OrderOutbox row) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.claim(row.getId(), now, now.plusSeconds(leaseSeconds)) != 1) {
            return;   // taken by another instance / thread, or no longer pending
        }
        try {
            SendResult result = producer.send(toMessage(row));
            if (result.getSendStatus() == SendStatus.SEND_OK) {
                mapper.markSent(row.getId(), LocalDateTime.now(clock));
                log.info("Outbox delivery succeeded outboxId={} eventId={} bizKey={} tenantId={} msgId={}",
                        row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), result.getMsgId());
            } else {
                onFailure(row, "sendStatus=" + result.getSendStatus());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure(row, "interrupted");
        } catch (Exception e) {
            onFailure(row, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void onFailure(OrderOutbox row, String error) {
        int newCount = row.getRetryCount() + 1;
        boolean giveUp = newCount >= maxRetries;
        LocalDateTime next = LocalDateTime.now(clock).plus(backoff(newCount));
        int updated = mapper.markRetry(row.getId(), row.getRetryCount(), newCount,
                giveUp ? OrderOutbox.FAILED : OrderOutbox.PENDING, next, truncate(error));
        if (giveUp) {
            // In production, hook up alerting here; after manual investigation, set status back to 0 to redeliver
            log.error("Outbox delivery failed {} times (limit reached), stopping automatic retry outboxId={} eventId={} bizKey={} tenantId={} error={}",
                    newCount, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), error);
        } else {
            log.warn("Outbox delivery failed, attempt {}, retrying in {} outboxId={} eventId={} bizKey={} tenantId={} updated={} error={}",
                    newCount, next, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), updated, error);
        }
    }

    /** After the n-th failure wait base x 2^(n-1), capped at max: brief MQ blips recover quickly, and long outages don't get hammered every second */
    Duration backoff(int failures) {
        long seconds = baseBackoffSeconds << Math.min(failures - 1, 20);
        return Duration.ofSeconds(Math.min(seconds, maxBackoffSeconds));
    }

    private Message toMessage(OrderOutbox row) {
        Message msg = new Message(row.getTopic(), row.getTag(), row.getPayload().getBytes(StandardCharsets.UTF_8));
        msg.setKeys(row.getBizKey() + " " + row.getEventId());
        // Use our own event_id as the idempotency key: RocketMQ's msgId changes on resend, event_id doesn't
        msg.putUserProperty(OrderEventTopics.PROP_EVENT_ID, String.valueOf(row.getEventId()));
        if (row.getDeliverAt() != null) {
            // RocketMQ 5 scheduled messages: delivered when the broker's timing wheel fires, millisecond precision (4.x only had 18 fixed delay levels).
            // Still set it on resend even if the delivery time has passed: the broker delivers immediately, timeout close runs right away, and the result is still correct
            msg.setDeliverTimeMs(row.getDeliverAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        // Use the outbox row's own tenant_id, not the thread context (which is SYSTEM here and represents no merchant);
        // stamp takes traceId from MDC, which publish has already opened from the row's trace_id
        return TenantMessages.stamp(msg, row.getTenantId());
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
