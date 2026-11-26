package com.merchanthub.order.mq.outbox;

import com.merchanthub.common.trace.TraceContext;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * order's generic outbox: writes an order_outbox row <b>inside the caller's business transaction</b>, sends once asynchronously right after commit, and the scan re-sends on failure.
 * Order created / paid / closed / timeout-close scheduled messages all go out through here -- "if the status changed there's always an event, if not there never is".
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OrderOutboxMapper outboxMapper;
    private final OutboxRelay relay;
    private final Clock clock;

    /**
     * MANDATORY: must join the business transaction. Committing separately would bring back the old problem of "order changed, event not written" or vice versa.
     *
     * @param deliverAt scheduled delivery time (UTC); null = deliver immediately
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long tenantId, long eventId, String bizKey, String topic, String tag,
                       String payload, LocalDateTime deliverAt) {
        OrderOutbox row = new OrderOutbox();
        row.setTenantId(tenantId);
        row.setEventId(eventId);
        row.setBizKey(bizKey);
        row.setTopic(topic);
        row.setTag(tag);
        row.setPayload(payload);
        row.setDeliverAt(deliverAt);
        // The actual send happens on another thread (post-commit pool / XXL-Job scan), where MDC no longer has this request's traceId,
        // so like tenant_id it's stored in the row, and the row itself decides which trace the message belongs to
        row.setTraceId(TraceContext.current());
        row.setStatus(OrderOutbox.PENDING);
        row.setRetryCount(0);
        // Scheduled messages are also "handed to the broker now" and delivered by the broker when due; next_retry_at only controls when we hand it to the broker
        row.setNextRetryAt(LocalDateTime.now(clock));
        outboxMapper.insert(row);

        // Must send after the transaction commits: sending inside it, the message could go out while the transaction rolls back -> "message sent, order never created"
        long outboxId = row.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.publishAsync(outboxId);
            }
        });
    }
}
