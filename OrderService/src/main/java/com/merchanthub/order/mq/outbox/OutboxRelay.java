package com.merchanthub.order.mq.outbox;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 本地消息表的投递与补偿：把 order_outbox 里 status = 0 的记录发到 MQ。
 * <p>
 * 两个入口走同一个 {@link #publish}：
 * <ul>
 *   <li>{@link #publishAsync}：订单事务提交后立即发一次（快路径）</li>
 *   <li>{@link #scan}：定时扫描到期记录补发（兜底：MQ 不可用、进程在发送前挂掉、快路径任务被丢弃）</li>
 * </ul>
 * 多个实例同时扫描时，靠 {@link OrderOutboxMapper#claim} 的条件更新保证同一条记录同一时刻只有一个实例在发。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "OUTBOX")
public class OutboxRelay {

    private final OrderOutboxMapper mapper;
    private final DefaultMQProducer producer;
    private final ExecutorService executor;
    private final Clock clock;

    /** 扫描开关。集成测试里，非补偿类的测试把它关掉，避免多个缓存的 Spring 上下文互相抢着补发（pitfalls P17 同类问题） */
    @Value("${merchanthub.order.outbox.scan-enabled:true}")
    private boolean scanEnabled;
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

    public OutboxRelay(OrderOutboxMapper mapper, DefaultMQProducer producer,
                       @Qualifier("outboxSendExecutor") ExecutorService executor, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.clock = clock;
    }

    /** 快路径：提交后立即异步发送，不占用下单请求的线程 */
    public void publishAsync(long outboxId) {
        executor.execute(() -> {
            OrderOutbox row = mapper.selectById(outboxId);
            if (row != null && row.getStatus() == OrderOutbox.PENDING) {
                publish(row);
            }
        });
    }

    /** 兜底：定时扫描到期的待发送记录 */
    @Scheduled(fixedDelayString = "${merchanthub.order.outbox.scan-interval-ms:5000}")
    public void scan() {
        if (!scanEnabled) {
            return;
        }
        List<OrderOutbox> due = mapper.selectDue(LocalDateTime.now(clock), batchSize);
        if (!due.isEmpty()) {
            log.info("本地消息表扫描：{} 条到期待发送", due.size());
        }
        for (OrderOutbox row : due) {
            publish(row);   // 每条单独处理、单独 catch：一条失败不影响同批其他记录
        }
    }

    public void publish(OrderOutbox row) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.claim(row.getId(), now, now.plusSeconds(leaseSeconds)) != 1) {
            return;   // 被别的实例 / 线程抢走了，或者已经不是待发送状态
        }
        try {
            SendResult result = producer.send(toMessage(row));
            if (result.getSendStatus() == SendStatus.SEND_OK) {
                mapper.markSent(row.getId(), LocalDateTime.now(clock));
                log.info("本地消息表投递成功 outboxId={} eventId={} bizKey={} tenantId={} msgId={}",
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
            // 生产环境这里接告警，人工排查后把 status 改回 0 即可重新投递
            log.error("本地消息表投递失败已达上限 {} 次，停止自动重试 outboxId={} eventId={} bizKey={} tenantId={} error={}",
                    newCount, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), error);
        } else {
            log.warn("本地消息表投递失败，第 {} 次，{} 后重试 outboxId={} eventId={} bizKey={} tenantId={} updated={} error={}",
                    newCount, next, row.getId(), row.getEventId(), row.getBizKey(), row.getTenantId(), updated, error);
        }
    }

    /** 第 n 次失败后等待 base × 2^(n-1)，封顶 max：MQ 短暂抖动很快恢复，长时间故障时不会每秒狂刷 */
    Duration backoff(int failures) {
        long seconds = baseBackoffSeconds << Math.min(failures - 1, 20);
        return Duration.ofSeconds(Math.min(seconds, maxBackoffSeconds));
    }

    private Message toMessage(OrderOutbox row) {
        Message msg = new Message(row.getTopic(), row.getTag(), row.getPayload().getBytes(StandardCharsets.UTF_8));
        msg.setKeys(row.getBizKey() + " " + row.getEventId());
        // 用我们自己的 event_id 做幂等键：重发时 RocketMQ 的 msgId 会变，event_id 不变（B2 笔记 Q4）
        msg.putUserProperty(OrderEventTopics.PROP_EVENT_ID, String.valueOf(row.getEventId()));
        return msg;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
