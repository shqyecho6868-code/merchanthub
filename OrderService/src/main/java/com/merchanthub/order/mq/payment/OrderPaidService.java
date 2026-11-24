package com.merchanthub.order.mq.payment;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mapper.PayAnomalyMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.entity.PayAnomaly;
import com.merchanthub.order.model.enums.OrderEvent;
import com.merchanthub.order.model.enums.OrderStatus;
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

import java.util.Objects;

/**
 * 收到"支付成功"后由 order 自己把订单改成已支付。payment 不直接改订单：订单状态只有 order 一个写入方，
 * 所有流转都经过同一个状态机和 CAS，不会出现两个服务各改各的。
 * <p>
 * 去重记录、状态 CAS、状态流水在同一个本地事务里（同 B2 的消费端幂等）。
 * B3-下：CAS 赢了 → 同一事务写 ORDER_PAID 事件（库存确认扣减）；CAS 输了 → 同一事务写 pay_anomaly（钱收了、单没成，留给对账）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaidService {

    public static final String CONSUMER_GROUP = "order-pay-success";

    private final ConsumedEventMapper consumedEventMapper;
    private final OrdersMapper ordersMapper;
    private final OrderStatusTransitioner transitioner;
    private final PayAnomalyMapper anomalyMapper;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /** @return true 订单本次被改成已支付；false 重复事件或订单状态不允许，什么也没改 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(PaySuccessEvent event) {
        ConsumedEvent mark = new ConsumedEvent();
        mark.setTenantId(event.tenantId());
        mark.setConsumerGroup(CONSUMER_GROUP);
        mark.setEventId(event.eventId());
        try {
            consumedEventMapper.insert(mark);
        } catch (DuplicateKeyException e) {
            log.info("【幂等·第三层 消费去重】重复的支付成功事件，跳过 eventId={} payNo={} orderNo={} tenantId={}",
                    event.eventId(), event.payNo(), event.orderNo(), event.tenantId());
            return false;
        }

        Orders order = ordersMapper.selectByOrderNoAcrossTenants(event.orderNo());
        if (order == null) {
            // 回调早于订单：订单可能马上就提交。抛异常 → 事务回滚（去重记录一起撤销）→ RocketMQ 重投（10s、30s、1m…）。
            // 订单始终不出现：重试 16 次（累计约 4 小时 46 分）后进死信队列 %DLQ%order-pay-success，交给 E3 对账
            log.warn("【回调早于订单】支付成功事件到达时订单尚不存在，回滚去重记录、稍后重投 payNo={} orderNo={} tenantId={}",
                    event.payNo(), event.orderNo(), event.tenantId());
            throw new OrderNotVisibleException(event.orderNo());
        }
        if (!Objects.equals(order.getTenantId(), event.tenantId()) || !Objects.equals(order.getPayAmount(), event.amount())) {
            // 商家不符 / 金额不符：不是重试能解决的，记下来交给对账，消息确认掉
            log.error("支付成功事件与订单不匹配，需对账 payNo={} orderNo={} 事件tenantId={} 事件金额={} 订单={}",
                    event.payNo(), event.orderNo(), event.tenantId(), event.amount(), order);
            recordAnomaly(event, PayAnomaly.MISMATCH, order.getStatus(), "订单tenantId=" + order.getTenantId()
                    + " 订单金额=" + order.getPayAmount());
            return false;
        }

        boolean ok = transitioner.transit(event.orderNo(), event.tenantId(), OrderEvent.PAY_SUCCESS,
                "SYSTEM", 0L, "payNo=" + event.payNo() + " channelTradeNo=" + event.channelTradeNo());
        if (!ok) {
            // 例：订单已超时关单后钱才到；或同一订单两张支付单都付成功了。订单不能"复活"，也不动库存，
            // 只记一条支付异常留给对账退款（E3 / 退款范围外）
            // 必须当前读：普通 SELECT 会读到本事务开头的快照（仍是待支付），见 OrdersMapper#selectStatusForUpdate
            int status = ordersMapper.selectStatusForUpdate(event.orderNo());
            String type = status == OrderStatus.CLOSED.getCode() ? PayAnomaly.PAID_AFTER_CLOSED : PayAnomaly.DUPLICATE_PAYMENT;
            log.error("【幂等·第四层 订单 CAS】订单当前状态 {} 不是待支付，支付成功未生效，记支付异常 {} 待对账 payNo={} orderNo={} tenantId={}",
                    status, type, event.payNo(), event.orderNo(), event.tenantId());
            recordAnomaly(event, type, status, "channelTradeNo=" + event.channelTradeNo());
            return false;
        }
        // 和 CAS 在同一个事务里写"已支付"事件：订单成了已支付，库存确认扣减就一定会发生
        OrderSettledEvent paid = new OrderSettledEvent(idGenerator.nextId(), event.orderNo(), event.tenantId(),
                OrderEvent.PAY_SUCCESS.name(), order.getBuyerId());
        outboxWriter.append(event.tenantId(), paid.eventId(), String.valueOf(event.orderNo()), OrderEventTopics.TOPIC,
                OrderEventTopics.TAG_ORDER_PAID, codec.toJson(paid), null);
        return true;
    }

    private void recordAnomaly(PaySuccessEvent event, String type, Integer orderStatus, String remark) {
        PayAnomaly a = new PayAnomaly();
        a.setTenantId(event.tenantId());
        a.setPayNo(event.payNo());
        a.setOrderNo(event.orderNo());
        a.setAmount(event.amount());
        a.setType(type);
        a.setOrderStatus(orderStatus);
        a.setHandleStatus(0);
        a.setRemark(remark);
        anomalyMapper.insert(a);
    }
}
