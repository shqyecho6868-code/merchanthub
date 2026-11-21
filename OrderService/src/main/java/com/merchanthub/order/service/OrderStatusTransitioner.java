package com.merchanthub.order.service;

import com.merchanthub.order.mapper.OrderStatusLogMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.OrderStatusLog;
import com.merchanthub.order.model.enums.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 订单状态流转的唯一入口：CAS 改状态 + 写状态流水。以后取消、超时关单、发货都走这里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusTransitioner {

    private final OrdersMapper ordersMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final Clock clock;

    /**
     * MANDATORY：必须在调用方已开启的事务里执行，没有事务直接报错。
     * 状态更新和流水要么一起提交、要么一起回滚；调用方（如消费去重）也要和它们在同一个事务里。
     *
     * @return true 本次流转成功；false 订单当前不处于 event.from（已被别的事件改过），什么也没改
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transit(long orderNo, long tenantId, OrderEvent event,
                           String operatorType, long operatorId, String remark) {
        int from = event.getFrom().getCode();
        int to = event.getTo().getCode();

        int rows = ordersMapper.casStatus(orderNo, tenantId, from, to, LocalDateTime.now(clock));
        if (rows != 1) {
            // CAS 失败 = 订单没变，就不能写流水：流水必须和真实发生过的流转一一对应，否则对账时"流水说已支付、订单却是已关闭"
            log.warn("订单状态 CAS 失败：订单当前不是 {}，{} 未生效 orderNo={} tenantId={}",
                    event.getFrom(), event, orderNo, tenantId);
            return false;
        }

        // 只有真正流转了才写流水，且和状态更新在同一个事务里（MANDATORY 保证）
        OrderStatusLog row = new OrderStatusLog();
        row.setTenantId(tenantId);
        row.setOrderNo(orderNo);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setEvent(event.name());
        row.setOperatorType(operatorType);
        row.setOperatorId(operatorId);
        row.setRemark(remark);
        statusLogMapper.insert(row);

        log.info("订单状态流转 {} → {} event={} orderNo={} tenantId={} remark={}",
                event.getFrom(), event.getTo(), event, orderNo, tenantId, remark);
        return true;
    }
}
