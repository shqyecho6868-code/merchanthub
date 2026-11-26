package com.merchanthub.payment.client;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.payment.client.dto.OrderBrief;
import com.merchanthub.payment.client.dto.PayCheckItem;
import com.merchanthub.payment.client.dto.PayCheckResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Calls order's internal APIs (/internal/** is not exposed through the gateway). Read-only: payment can't change order status, see B3 notes.
 * Timeouts are in application.yml; E3 (C2 follow-up): every call is wrapped in a Sentinel circuit breaker via {@link OrderRemote}, resource names below.
 */
@FeignClient(name = "order", contextId = "paymentOrderClient", path = "/internal/orders",
        configuration = OrderFeignConfig.class)
public interface OrderClient {

    String RES_GET_ORDER = "order:getOrder";
    String RES_PAY_CHECK = "order:payCheck";
    String RES_STATUSES = "order:statuses";

    @GetMapping("/{orderNo}")
    BaseResponse<OrderBrief> getOrder(@PathVariable("orderNo") long orderNo);

    /** E3 reconciliation: for each succeeded payment, has order processed its event */
    @PostMapping("/pay-check")
    BaseResponse<List<PayCheckResult>> payCheck(@RequestBody List<PayCheckItem> items);

    /** E3 reconciliation: batch-query order statuses (missing orders are not returned) */
    @PostMapping("/statuses")
    BaseResponse<Map<Long, Integer>> statuses(@RequestBody List<Long> orderNos);
}
