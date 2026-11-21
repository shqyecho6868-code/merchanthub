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

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 批量查哪些订单存在（product-inventory 的悬空预占扫描用）。只回订单号，不回订单内容。
     * 一次最多 500 个：防止调用方一次传太多拖慢 order
     */
    @PostMapping("/existing")
    public BaseResponse<List<Long>> existing(@RequestBody List<Long> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return ResultUtils.success(List.of());
        }
        if (orderNos.size() > 500) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "一次最多查询 500 个订单号");
        }
        List<Long> found = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                        .select(Orders::getOrderNo).in(Orders::getOrderNo, orderNos))
                .stream().map(Orders::getOrderNo).toList();
        return ResultUtils.success(found);
    }
}
