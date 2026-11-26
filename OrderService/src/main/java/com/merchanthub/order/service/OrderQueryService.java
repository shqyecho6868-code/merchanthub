package com.merchanthub.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.vo.OrderDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Order queries. Two entry points with different isolation:
 * <ul>
 *   <li>Merchant query: the code does <b>not</b> write a tenant_id condition; the tenant plugin adds it -- if merchant 7 queries merchant 8's order number, the SQL becomes
 *       {@code WHERE order_no = ? AND tenant_id = 7} and finds nothing</li>
 *   <li>Buyer query: buyers belong to no merchant, so the tenant plugin adds no condition; the code filters explicitly by buyer_id instead (data ownership check)</li>
 * </ul>
 * "Not found" and "not yours" both return the same "Order not found": callers can't probe which order numbers really exist from different errors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrdersMapper ordersMapper;

    public OrderDetailVO getForMerchant(long orderNo) {
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo));
        return toVO(orderNo, o);
    }

    public OrderDetailVO getForBuyer(long buyerId, long orderNo) {
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, orderNo).eq(Orders::getBuyerId, buyerId));
        return toVO(orderNo, o);
    }

    private static OrderDetailVO toVO(long orderNo, Orders o) {
        if (o == null) {
            log.info("Order query miss (not found or not owned by current identity) orderNo={}", orderNo);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Order not found");
        }
        return new OrderDetailVO(String.valueOf(o.getOrderNo()), o.getTenantId(), o.getBuyerId(), o.getStatus(),
                o.getPayAmount(), o.getCreatedAt());
    }
}
