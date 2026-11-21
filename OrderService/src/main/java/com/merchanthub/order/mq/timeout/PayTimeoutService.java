package com.merchanthub.order.mq.timeout;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.enums.OrderEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.mq.OrderSettledEvent;
import com.merchanthub.order.mq.outbox.OutboxWriter;
import com.merchanthub.order.service.OrderStatusTransitioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时关单：定时消息到期 → CAS 待支付→已关闭 → 赢了才写"订单已关闭"事件（库存据此释放）。
 * <p>
 * 和支付成功竞争同一个订单时，靠 {@link OrderStatusTransitioner} 的 CAS 只让一方赢：
 * 输家什么也不写，所以也就不会产生任何库存事件 —— "输家不改库存"不是靠输家自觉，而是它根本没有事件可发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayTimeoutService {

    public static final String CONSUMER_GROUP = "order-pay-timeout";

    private final ConsumedEventMapper consumedEventMapper;
    private final OrderStatusTransitioner transitioner;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /** @return true 本次把订单关掉了；false 重复消息或订单已不是待支付（已支付 / 已取消），什么也没改 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(PayTimeoutMessage msg) {
        ConsumedEvent mark = new ConsumedEvent();
        mark.setTenantId(msg.tenantId());
        mark.setConsumerGroup(CONSUMER_GROUP);
        mark.setEventId(msg.eventId());
        try {
            consumedEventMapper.insert(mark);
        } catch (DuplicateKeyException e) {
            log.info("重复的超时关单消息，跳过 eventId={} orderNo={} tenantId={}", msg.eventId(), msg.orderNo(), msg.tenantId());
            return false;
        }

        boolean won = transitioner.transit(msg.orderNo(), msg.tenantId(), OrderEvent.PAY_TIMEOUT, "SYSTEM", 0L, "支付超时");
        if (!won) {
            // 常见原因：已经支付（支付成功先赢了 CAS）。正常结束，不关单、不发事件、不动库存
            log.info("【超时关单·CAS 输】订单已不是待支付，不关单、不释放库存 orderNo={} tenantId={}", msg.orderNo(), msg.tenantId());
            return false;
        }
        // 和关单 CAS 在同一个事务里写"已关闭"事件：关单提交了，库存释放就一定会发生（进程挂了扫描补发）
        OrderSettledEvent closed = new OrderSettledEvent(idGenerator.nextId(), msg.orderNo(), msg.tenantId(),
                OrderEvent.PAY_TIMEOUT.name());
        outboxWriter.append(msg.tenantId(), closed.eventId(), String.valueOf(msg.orderNo()), OrderEventTopics.TOPIC,
                OrderEventTopics.TAG_ORDER_CLOSED, codec.toJson(closed), null);
        log.info("【超时关单·CAS 赢】待支付→已关闭，已写 ORDER_CLOSED 事件（库存将释放）orderNo={} tenantId={}",
                msg.orderNo(), msg.tenantId());
        return true;
    }
}
