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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Delivery and compensation for the local message table: sends order_outbox records with status = 0 to MQ.
 * <p>
 * Two entry points share the same {@link #publish}:
 * <ul>
 *   <li>{@link #publishAsync}: sent once right after the order transaction commits (fast path)</li>
 *   <li>{@link #scan}: periodically re-sends due records (safety net: MQ unavailable, process died before sending, fast-path task dropped).
 *       Scheduled by XXL-Job ({@code orderOutboxRelayJob}) instead of Spring @Scheduled</li>
 * </ul>
 * When several instances scan at once, the conditional update in {@link OrderOutboxMapper#claim} ensures only one instance sends a given record at a time.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OrderOutboxMapper mapper;
    private final DefaultMQProducer producer;
    private final ExecutorService executor;
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

    // In TX_MESSAGE mode there is also a transactional producer (TransactionMQProducer extends DefaultMQProducer), so select the plain producer by name
    public OutboxRelay(OrderOutboxMapper mapper, @Qualifier("orderOutboxProducer") DefaultMQProducer producer,
                       @Qualifier("outboxSendExecutor") ExecutorService executor, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Fast path: send asynchronously right after commit, without holding the order request thread.
     * Pool threads do <b>not</b> have the committing thread's tenant context (ThreadLocal doesn't cross threads), so use SYSTEM explicitly:
     * the outbox is infrastructure and processes any merchant's records by id; which merchant a message belongs to comes from the outbox row's tenant_id (see toMessage)
     */
    public void publishAsync(long outboxId) {
        executor.execute(() -> TenantContext.runAsSystem(() -> {
            OrderOutbox row = mapper.selectById(outboxId);
            if (row != null && row.getStatus() == OrderOutbox.PENDING) {
                publish(row);
            }
        }));
    }

    /**
     * Safety net: scan due pending records. Triggered by XXL-Job (the admin guarantees only one instance is invoked at a time);
     * even triggering on two instances manually is safe: the conditional update in claim lets only one instance send each record.
     *
     * @return number of due records found in this round
     */
    public int scan() {
        // The job thread has no context; the scan spans all merchants anyway, so declare SYSTEM explicitly (otherwise the tenant plugin throws)
        return TenantContext.callAsSystem(this::scanOnce);
    }

    private int scanOnce() {
        List<OrderOutbox> due = mapper.selectDue(LocalDateTime.now(clock), batchSize);
        if (!due.isEmpty()) {
            log.info("Outbox scan: {} due records pending send", due.size());
        }
        for (OrderOutbox row : due) {
            publish(row);   // each record handled and caught separately: one failure doesn't affect the rest of the batch
        }
        return due.size();
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
