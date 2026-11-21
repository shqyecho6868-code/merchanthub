package com.merchanthub.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.vo.OrderBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务间内部接口（/internal/** 网关不对外转发）。只读：别的服务不能通过接口改订单状态 */
@RestController
@RequestMapping("/internal/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrdersMapper ordersMapper;

    @GetMapping("/{orderNo}")
    public BaseResponse<OrderBriefVO> get(@PathVariable long orderNo) {
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo));
        if (o == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "订单不存在");
        }
        return ResultUtils.success(new OrderBriefVO(o.getOrderNo(), o.getTenantId(), o.getBuyerId(), o.getStatus(), o.getPayAmount()));
    }
}
