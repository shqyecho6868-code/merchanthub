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
 * 审批结果发件箱：在<b>完成审批任务的那个事务里</b>写一行，提交后立即异步发一次，失败由扫描补发（同 order 的 OutboxWriter，B2）。
 * <p>
 * 能做到"审批通过 ⇔ 一定有消息"，关键是 Flowable 和 MyBatis-Plus 用的是同一个 DataSource、同一个 Spring 事务：
 * 引擎删 ACT_RU_TASK、写 ACT_HI_*，和这里 INSERT approval_outbox，要么一起提交，要么一起回滚。
 */
@Component
@RequiredArgsConstructor
public class ApprovalOutboxWriter {

    private final ApprovalOutboxMapper outboxMapper;
    private final ApprovalOutboxRelay relay;
    private final Clock clock;

    /** MANDATORY：必须加入业务事务；单独提交就又回到"流程结束了、消息没写"或反过来 */
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
