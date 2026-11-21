package com.merchanthub.payment.client;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.payment.client.dto.OrderBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 调 order 的内部接口（/internal/** 不经网关对外暴露）。只读：payment 不能改订单状态，见 B3 笔记 */
@FeignClient(name = "order")
public interface OrderClient {

    @GetMapping("/internal/orders/{orderNo}")
    BaseResponse<OrderBrief> getOrder(@PathVariable("orderNo") long orderNo);
}
