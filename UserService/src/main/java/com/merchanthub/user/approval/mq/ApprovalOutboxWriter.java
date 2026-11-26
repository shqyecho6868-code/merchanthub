package com.merchanthub.user.approval.mq;

import com.merchanthub.user.approval.mapper.ApprovalOutboxMapper;
import com.merchanthub.user.approval.model.entity.ApprovalOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Approval result outbox: write a row <b>in the same transaction that completes the approval task</b>, send once asynchronously right after commit, and let the scan resend on failure (same as order's OutboxWriter).
 * <p>
 * "Approved <=> a message is guaranteed" works because Flowable and MyBatis-Plus share the same DataSource and the same Spring transaction:
 * the engine deleting ACT_RU_TASK and writing ACT_HI_*, and our INSERT into approval_outbox, either commit together or roll back together.
 */
@Component
@RequiredArgsConstructor
public class ApprovalOutboxWriter {

    private final ApprovalOutboxMapper outboxMapper;
    private final ApprovalOutboxRelay relay;
    private final Clock clock;

    /** MANDATORY: must join the business transaction; committing separately brings back "process ended but no message written" or the reverse */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long tenantId, long eventId, String bizKey, String topic, String tag, String payload) {
        ApprovalOutbox row = new ApprovalOutbox();
        row.setTenantId(tenantId);
        row.setEventId(eventId);
        row.setBizKey(bizKey);
        row.setTopic(topic);
        row.setTag(tag);
        row.setPayload(payload);
        row.setStatus(ApprovalOutbox.PENDING);
        row.setRetryCount(0);
        row.setNextRetryAt(LocalDateTime.now(clock));
        outboxMapper.insert(row);

        long outboxId = row.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.publishAsync(outboxId);
            }
        });
    }
}
