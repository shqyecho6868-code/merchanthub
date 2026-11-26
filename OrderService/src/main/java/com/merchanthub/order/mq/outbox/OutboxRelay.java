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
 * 本地消息表的投递与补偿：把 order_outbox 里 status = 0 的记录发到 MQ。
 * <p>
 * 两个入口走同一个 {@link #publish}：
 * <ul>
 *   <li>{@link #publishAsync}：订单事务提交后立即发一次（快路径）</li>
 *   <li>{@link #scan}：定时扫描到期记录补发（兜底：MQ 不可用、进程在发送前挂掉、快路径任务被丢弃）。
 *       E3 起由 XXL-Job 调度（{@code orderOutboxRelayJob}），不再用 Spring @Scheduled</li>
 * </ul>
 * 多个实例同时扫描时，靠 {@link OrderOutboxMapper#claim} 的条件更新保证同一条记录同一时刻只有一个实例在发。
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

    // TX_MESSAGE 模式下还有一个事务生产者（TransactionMQProducer 也是 DefaultMQProducer），按名字指定用普通生产者
    public OutboxRelay(OrderOutboxMapper mapper, @Qualifier("orderOutboxProducer") DefaultMQProducer producer,
                       @Qualifier("outboxSendExecutor") ExecutorService executor, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * 快路径：提交后立即异步发送，不占用下单请求的线程。
     * 线程池的线程<b>没有</b>提交事务那个线程的租户上下文（ThreadLocal 不跨线程），这里显式用 SYSTEM：
     * 发件箱是基础设施，按 id 处理任意商家的记录；消息属于哪个商家由 outbox 行的 tenant_id 决定（见 toMessage）
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
     * 兜底：扫描到期的待发送记录。E3 起由 XXL-Job 触发（调度中心保证同一时刻只调一台实例）；
     * 即使手动在两台上同时触发也安全：每条记录靠 claim 的条件更新只会被一个实例发送。
     *
     * @return 本轮扫到的到期记录数
     */
    public int scan() {
        // 任务线程没有任何上下文；扫描本来就跨所有商家，显式声明 SYSTEM（不声明的话租户插件会直接报错）
        return TenantContext.callAsSystem(this::scanOnce);
    }

    private int scanOnce() {
        List<OrderOutbox> due = mapper.selectDue(LocalDateTime.now(clock), batchSize);
        if (!due.isEmpty()) {
            log.info("本地消息表扫描：{} 条到期待发送", due.size());
        }
        for (OrderOutbox row : due) {
            publish(row);   // 每条单独处理、单独 catch：一条失败不影响同批其他记录
        }
        return due.size();
    }

    public void publish(OrderOutbox row) {
        // 每条记录单独打开它自己的 traceId（写入时那个请求的），处理完还原：
        // 投递日志能和下单请求串起来，stamp 也会把它写进消息属性；扫描一批时一条一换，不会串到下一条（老数据没有 trace_id 就新生成）
        try (TraceContext.Scope ignored = TraceContext.open(row.getTraceId())) {
            doPublish(row);
        }
    }

    private void doPublish(OrderOutbox row) {
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
        if (row.getDeliverAt() != null) {
            // RocketMQ 5 定时消息：Broker 的时间轮到点才投递，精度到毫秒（4.x 只有 18 个固定延迟等级）。
            // 补发时投递时间已经过去也照样设：Broker 会立即投递，超时关单马上执行，结果仍然正确
            msg.setDeliverTimeMs(row.getDeliverAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        // C1：用 outbox 行自己的 tenant_id，不用线程上下文（这里是 SYSTEM，不代表任何商家）；
        // traceId 由 stamp 从 MDC 取，publish 已按行的 trace_id 打开
        return TenantMessages.stamp(msg, row.getTenantId());
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
