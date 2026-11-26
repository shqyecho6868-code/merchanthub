package com.merchanthub.user.approval.mq;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.user.approval.mapper.ApprovalOutboxMapper;
import com.merchanthub.user.approval.model.entity.ApprovalOutbox;
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
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Delivery and compensation for the approval-result local outbox table (copied from order's OutboxRelay, B2): sends approval_outbox records with status = 0 to MQ.
 * <p>
 * Both entry points go through the same {@link #publish}:
 * <ul>
 *   <li>{@link #publishAsync}: send once right after the approval transaction commits (fast path)</li>
 *   <li>{@link #scan}: periodically scan due records and resend (safety net: MQ unavailable, process died before sending, fast-path task dropped)</li>
 * </ul>
 * When several instances scan at once, the conditional update in {@link ApprovalOutboxMapper#claim} ensures only one instance sends a given record at a time.
 */
@Slf4j
@Component
public class ApprovalOutboxRelay {

    private final ApprovalOutboxMapper mapper;
    private final DefaultMQProducer producer;
    private final ExecutorService executor;
    private final Clock clock;
    @Value("${merchanthub.approval.outbox.batch-size:100}")
    private int batchSize;
    @Value("${merchanthub.approval.outbox.lease-seconds:30}")
    private long leaseSeconds;
    @Value("${merchanthub.approval.outbox.base-backoff-seconds:10}")
    private long baseBackoffSeconds;
    @Value("${merchanthub.approval.outbox.max-backoff-seconds:600}")
    private long maxBackoffSeconds;
    @Value("${merchanthub.approval.outbox.max-retries:10}")
    private int maxRetries;

    public ApprovalOutboxRelay(ApprovalOutboxMapper mapper, @Qualifier("approvalOutboxProducer") DefaultMQProducer producer,
                       @Qualifier("approvalOutboxSendExecutor") ExecutorService executor, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Fast path: send asynchronously right after commit, without occupying the approval request's thread.
     * Pool threads do <b>not</b> have the committing thread's tenant context (ThreadLocal doesn't cross threads), so SYSTEM is used explicitly here:
     * the outbox is infrastructure and processes records of any merchant by id; which merchant a message belongs to is decided by the outbox row's tenant_id (see toMessage)
     */
    public void publishAsync(long outboxId) {
        executor.execute(() -> TenantContext.runAsSystem(() -> {
            ApprovalOutbox row = mapper.selectById(outboxId);
            if (row != null && row.getStatus() == ApprovalOutbox.PENDING) {
                publish(row);
            }
        }));
    }

    /** Safety net: scan pending records that are due. Since E3 triggered by XXL-Job's approvalOutboxRelayJob (formerly @Scheduled every 5 seconds) */
    public int scan() {
        // The job thread has no context at all; the scan spans all merchants anyway, so declare SYSTEM explicitly (otherwise the tenant plugin throws)
        return TenantContext.callAsSystem(this::scanOnce);
    }

    private int scanOnce() {
        List<ApprovalOutbox> due = mapper.selectDue(LocalDateTime.now(clock), batchSize);
        if (!due.isEmpty()) {
            log.info("Outbox scan: {} records due for sending", due.size());
        }
        for (ApprovalOutbox row : due) {
            publish(row);   // each record handled and caught separately: one failure doesn't affect the rest of the batch
        }
        return due.size();
    }

    public void publish(ApprovalOutbox row) {
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

    private void onFailure(ApprovalOutbox row, String error) {
        int newCount = row.getRetryCount() + 1;
        boolean giveUp = newCount >= maxRetries;
        LocalDateTime next = LocalDateTime.now(clock).plus(backoff(newCount));
        int updated = mapper.markRetry(row.getId(), row.getRetryCount(), newCount,
                giveUp ? ApprovalOutbox.FAILED : ApprovalOutbox.PENDING, next, truncate(error));
        if (giveUp) {
            // In production hook an alert here; after manual investigation, set status back to 0 to redeliver
            log.error("Outbox delivery failed {} times (limit reached), stopping automatic retries outboxId={} eventId={} bizKey={} tenantId={} error={}",
                    newCount, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), error);
        } else {
            log.warn("Outbox delivery failed, attempt {}, retry at {} outboxId={} eventId={} bizKey={} tenantId={} updated={} error={}",
                    newCount, next, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), updated, error);
        }
    }

    /** After the n-th failure wait base x 2^(n-1), capped at max: brief MQ blips recover fast, and long outages don't hammer every second */
    Duration backoff(int failures) {
        long seconds = baseBackoffSeconds << Math.min(failures - 1, 20);
        return Duration.ofSeconds(Math.min(seconds, maxBackoffSeconds));
    }

    private Message toMessage(ApprovalOutbox row) {
        Message msg = new Message(row.getTopic(), row.getTag(), row.getPayload().getBytes(StandardCharsets.UTF_8));
        msg.setKeys(row.getBizKey() + " " + row.getEventId());
        // Use our own event_id as the idempotency key: RocketMQ's msgId changes on resend, event_id doesn't (B2 notes Q4)
        msg.putUserProperty("EVENT_ID", String.valueOf(row.getEventId()));
        // C1: use the outbox row's own tenant_id, not the thread context (which is SYSTEM here and represents no merchant)
        return TenantMessages.stamp(msg, row.getTenantId());
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
