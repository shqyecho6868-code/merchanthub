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
 * The single entry point for order status transitions: CAS the status + write the status log. Cancel, timeout close, and shipping will all go through here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusTransitioner {

    private final OrdersMapper ordersMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final Clock clock;

    /**
     * MANDATORY: must run inside a transaction already opened by the caller; fails immediately without one.
     * The status update and the log commit or roll back together; the caller (e.g. consumer dedup) must also be in the same transaction.
     *
     * @return true if this transition succeeded; false if the order isn't currently in event.from (already changed by another event), nothing changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transit(long orderNo, long tenantId, OrderEvent event,
                           String operatorType, long operatorId, String remark) {
        int from = event.getFrom().getCode();
        int to = event.getTo().getCode();

        int rows = ordersMapper.casStatus(orderNo, tenantId, from, to, LocalDateTime.now(clock), event.name());
        if (rows != 1) {
            // CAS failed = order unchanged, so no log entry: the log must map one-to-one to transitions that actually happened, otherwise reconciliation sees "log says paid, order is closed"
            log.warn("订单状态 CAS 失败：订单当前不是 {}，{} 未生效 orderNo={} tenantId={}",
                    event.getFrom(), event, orderNo, tenantId);
            return false;
        }

        // Write the log only for a real transition, in the same transaction as the status update (guaranteed by MANDATORY)
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
