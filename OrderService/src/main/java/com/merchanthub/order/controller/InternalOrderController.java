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

/** Internal service-to-service APIs (/internal/** is not forwarded by the gateway). Read-only: other services can't change order status through APIs */
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
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Order not found");
        }
        return ResultUtils.success(new OrderBriefVO(o.getOrderNo(), o.getTenantId(), o.getBuyerId(), o.getStatus(), o.getPayAmount()));
    }


    /**
     * E3: batch-query order statuses (used by product-inventory reservation reconciliation). Nonexistent orders are omitted from the result.
     * Returns order number -> status code; the caller decides: paid -> confirm deduction; closed / not found -> release; pending -> leave as is
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
     * E3: for payment reconciliation -- for each "payment succeeded" payment, has order processed its event?
     * Decided by the consumer dedup table (eventId = payNo) rather than order status: a paid order may have been paid by a <b>different</b> payment,
     * so looking only at status would miss "duplicate payment with a lost event"; the dedup table answers precisely "did this payment's event ever reach order"
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
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "At most " + MAX_BATCH + " items per query");
        }
    }
}
