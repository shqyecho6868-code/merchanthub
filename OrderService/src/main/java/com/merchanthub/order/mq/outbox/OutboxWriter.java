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
 * order 的通用发件箱：在<b>调用方的业务事务里</b>写一行 order_outbox，提交后立即异步发一次，失败由扫描补发。
 * 订单已创建 / 已支付 / 已关闭 / 超时关单定时消息都从这里出去 —— "状态改了就一定有事件，没改就一定没有"。
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OrderOutboxMapper outboxMapper;
    private final OutboxRelay relay;
    private final Clock clock;

    /**
     * MANDATORY：必须加入业务事务。单独提交的话，就回到了"订单改了、事件没写"或反过来的老问题。
     *
     * @param deliverAt 定时投递时间（UTC）；null = 立即投递
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
        // 真正发送的是另一个线程（提交后的线程池 / XXL-Job 扫描），那时 MDC 里已经没有这个请求的 traceId，
        // 所以和 tenant_id 一样落进行里，由行自己决定消息属于哪条链路
        row.setTraceId(TraceContext.current());
        row.setStatus(OrderOutbox.PENDING);
        row.setRetryCount(0);
        // 定时消息也是"现在就发给 Broker"，由 Broker 到点再投递；next_retry_at 只管我们什么时候把它交给 Broker
        row.setNextRetryAt(LocalDateTime.now(clock));
        outboxMapper.insert(row);

        // 必须等事务提交后再发：在事务里发，消息出去了事务却可能回滚 → "消息发了订单没建"
        long outboxId = row.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.publishAsync(outboxId);
            }
        });
    }
}
