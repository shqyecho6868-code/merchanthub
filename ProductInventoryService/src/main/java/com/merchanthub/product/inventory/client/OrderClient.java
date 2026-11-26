package com.merchanthub.product.inventory.client;

import com.merchanthub.common.common.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/** Calls order's read-only internal API (/internal/** is not exposed through the gateway). Timeouts are in application.yml */
@FeignClient(name = "order", contextId = "inventoryOrderClient", path = "/internal/orders")
public interface OrderClient {

    /** E3: pass in a batch of order numbers, returns the status of those that <b>exist</b> (order number → status code). Batched: one call per reconciliation round */
    @PostMapping("/statuses")
    BaseResponse<Map<Long, Integer>> statuses(@RequestBody List<Long> orderNos);
}
