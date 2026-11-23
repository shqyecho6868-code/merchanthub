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
 * 订单查询（C1）。两个入口，隔离方式不同：
 * <ul>
 *   <li>商家查：代码里<b>不写</b> tenant_id 条件，租户插件自动拼上 —— 商家 7 拿商家 8 的订单号来查，SQL 变成
 *       {@code WHERE order_no = ? AND tenant_id = 7}，查不到</li>
 *   <li>买家查：买家不属于任何商家，租户插件不拼条件，改为代码里显式按 buyer_id 过滤（数据归属校验）</li>
 * </ul>
 * 查不到和"不是你的"都返回同一个"订单不存在"：不让对方通过不同的报错探测出哪些订单号是真实存在的。
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
            log.info("订单查询未命中（不存在或不属于当前身份）orderNo={}", orderNo);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "订单不存在");
        }
        return new OrderDetailVO(String.valueOf(o.getOrderNo()), o.getTenantId(), o.getBuyerId(), o.getStatus(),
                o.getPayAmount(), o.getCreatedAt());
    }
}
