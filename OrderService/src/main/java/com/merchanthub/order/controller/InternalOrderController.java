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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.model.dto.PayCheckItem;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.vo.PayCheckResult;
import com.merchanthub.order.mq.payment.OrderPaidService;
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
    private final ConsumedEventMapper consumedEventMapper;

    private static final int MAX_BATCH = 500;

    @GetMapping("/{orderNo}")
    public BaseResponse<OrderBriefVO> get(@PathVariable long orderNo) {
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo));
        if (o == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "订单不存在");
        }
        return ResultUtils.success(new OrderBriefVO(o.getOrderNo(), o.getTenantId(), o.getBuyerId(), o.getStatus(), o.getPayAmount()));
    }


    /**
     * E3：批量查订单状态（product-inventory 的预占对账用）。不存在的订单不出现在结果里。
     * 返回订单号 → 状态码，调用方据此决定：已支付 → 确认扣减；已关闭 / 不存在 → 释放；待支付 → 不动
     */
    @PostMapping("/statuses")
    public BaseResponse<Map<Long, Integer>> statuses(@RequestBody List<Long> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return ResultUtils.success(Map.of());
        }
        checkBatch(orderNos.size());
        Map<Long, Integer> result = new HashMap<>();
        ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                        .select(Orders::getOrderNo, Orders::getStatus).in(Orders::getOrderNo, orderNos))
                .forEach(o -> result.put(o.getOrderNo(), o.getStatus()));
        return ResultUtils.success(result);
    }

    /**
     * E3：payment 对账用 —— 每张"支付成功"的支付单，order 处理过它的事件没有。
     * 判断依据是消费去重表（eventId = payNo）而不是订单状态：订单已支付也可能是被<b>另一张</b>支付单付的，
     * 只看状态会漏掉"重复支付且事件丢失"；去重表能精确回答"这张支付单的事件到没到过 order"
     */
    @PostMapping("/pay-check")
    public BaseResponse<List<PayCheckResult>> payCheck(@RequestBody List<PayCheckItem> items) {
        if (items == null || items.isEmpty()) {
            return ResultUtils.success(List.of());
        }
        checkBatch(items.size());
        List<Long> orderNos = items.stream().map(PayCheckItem::orderNo).distinct().toList();
        List<Long> payNos = items.stream().map(PayCheckItem::payNo).toList();
        Map<Long, Integer> status = new HashMap<>();
        ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                        .select(Orders::getOrderNo, Orders::getStatus).in(Orders::getOrderNo, orderNos))
                .forEach(o -> status.put(o.getOrderNo(), o.getStatus()));
        Set<Long> processed = consumedEventMapper.selectList(new LambdaQueryWrapper<ConsumedEvent>()
                        .select(ConsumedEvent::getEventId)
                        .eq(ConsumedEvent::getConsumerGroup, OrderPaidService.CONSUMER_GROUP)
                        .in(ConsumedEvent::getEventId, payNos))
                .stream().map(ConsumedEvent::getEventId).collect(Collectors.toSet());
        return ResultUtils.success(items.stream()
                .map(i -> new PayCheckResult(i.payNo(), i.orderNo(), status.get(i.orderNo()), processed.contains(i.payNo())))
                .toList());
    }

    private static void checkBatch(int size) {
        if (size > MAX_BATCH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "一次最多查询 " + MAX_BATCH + " 条");
        }
    }
}
