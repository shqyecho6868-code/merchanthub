package com.merchanthub.order.mq.payment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.enums.OrderEvent;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaidService {

    public static final String CONSUMER_GROUP = "order-pay-success";

    private final ConsumedEventMapper consumedEventMapper;
    private final OrdersMapper ordersMapper;
    private final OrderStatusTransitioner transitioner;

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

        Orders order = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, event.orderNo()));
        if (order == null || !Objects.equals(order.getTenantId(), event.tenantId())
                || !Objects.equals(order.getPayAmount(), event.amount())) {
            // 订单不存在 / 商家不符 / 金额不符：不是重试能解决的，记 ERROR 交给对账，消息确认掉
            log.error("支付成功事件与订单不匹配，需对账 payNo={} orderNo={} 事件tenantId={} 事件金额={} 订单={}",
                    event.payNo(), event.orderNo(), event.tenantId(), event.amount(), order);
            return false;
        }

        boolean ok = transitioner.transit(event.orderNo(), event.tenantId(), OrderEvent.PAY_SUCCESS,
                "SYSTEM", 0L, "payNo=" + event.payNo() + " channelTradeNo=" + event.channelTradeNo());
        if (!ok) {
            // 例：订单已超时关单后钱才到；或同一订单两张支付单都付成功了。订单不能"复活"，走对账退款（B3 下 / 范围外）
            log.error("【幂等·第四层 订单 CAS】订单当前状态 {} 不是待支付，支付成功未生效，需对账 payNo={} orderNo={} tenantId={}",
                    order.getStatus(), event.payNo(), event.orderNo(), event.tenantId());
        }
        return ok;
    }
}
