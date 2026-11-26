package com.merchanthub.order.client;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.client.dto.SkuQueryRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.config.ProductInventoryFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Calls product-inventory's internal APIs. Timeouts are configured in application.yml (connect 1s / read 3s);
 * no automatic retry (see {@link ProductInventoryFeignConfig} for why); circuit breaking/degradation is done in {@link RemoteResults} by the resource names below.
 * The downstream returns the unified response body: HTTP 200 + body.code; business failures are read from code, see {@link RemoteResults}.
 */
@FeignClient(name = "product-inventory", contextId = "productInventoryClient", path = "/internal",
        configuration = ProductInventoryFeignConfig.class)
public interface ProductInventoryClient {

    /** Sentinel resource names (breaker rules are keyed by them), visible under "cluster point links" in the dashboard */
    String RES_QUERY_SKUS = "product-inventory:querySkus";
    String RES_RESERVE = "product-inventory:reserve";

    @PostMapping("/skus/query")
    BaseResponse<List<SkuSnapshot>> querySkus(@RequestBody SkuQueryRequest request);

    @PostMapping("/inventory/reserve")
    BaseResponse<Void> reserve(@RequestBody ReserveRequest request);
}
