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
 * 调 order 的内部接口（/internal/** 不经网关对外暴露）。只读：payment 不能改订单状态，见 B3 笔记。
 * 超时见 application.yml；E3（C2 遗留）：每次调用经 {@link OrderRemote} 包一层 Sentinel 熔断，资源名如下。
 */
@FeignClient(name = "order", contextId = "paymentOrderClient", path = "/internal/orders",
        configuration = OrderFeignConfig.class)
public interface OrderClient {

    String RES_GET_ORDER = "order:getOrder";
    String RES_PAY_CHECK = "order:payCheck";
    String RES_STATUSES = "order:statuses";

    @GetMapping("/{orderNo}")
    BaseResponse<OrderBrief> getOrder(@PathVariable("orderNo") long orderNo);

    /** E3 对账：每张支付成功的支付单，order 处理过它的事件没有 */
    @PostMapping("/pay-check")
    BaseResponse<List<PayCheckResult>> payCheck(@RequestBody List<PayCheckItem> items);

    /** E3 对账：批量查订单状态（不存在的不返回） */
    @PostMapping("/statuses")
    BaseResponse<Map<Long, Integer>> statuses(@RequestBody List<Long> orderNos);
}
