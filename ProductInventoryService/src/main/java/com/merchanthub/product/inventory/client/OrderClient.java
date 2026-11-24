package com.merchanthub.product.inventory.client;

import com.merchanthub.common.common.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/** 调 order 的只读内部接口（/internal/** 不经网关对外暴露）。超时见 application.yml */
@FeignClient(name = "order", contextId = "inventoryOrderClient", path = "/internal/orders")
public interface OrderClient {

    /** E3：传入一批订单号，返回其中<b>存在</b>的订单的状态（订单号 → 状态码）。批量，一轮对账只调一次 */
    @PostMapping("/statuses")
    BaseResponse<Map<Long, Integer>> statuses(@RequestBody List<Long> orderNos);
}
