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
 * 调用 product-inventory 的内部接口。超时在 application.yml 里配（连接 1s / 读 3s）；
 * 不自动重试（原因见 {@link ProductInventoryFeignConfig}）；熔断降级在 {@link RemoteResults} 里按下面的资源名做（C2）。
 * 下游返回的是统一返回体：HTTP 200 + body.code，业务失败要看 code，见 {@link RemoteResults}。
 */
@FeignClient(name = "product-inventory", contextId = "productInventoryClient", path = "/internal",
        configuration = ProductInventoryFeignConfig.class)
public interface ProductInventoryClient {

    /** Sentinel 资源名（熔断规则按它配），控制台"簇点链路"里能看到 */
    String RES_QUERY_SKUS = "product-inventory:querySkus";
    String RES_RESERVE = "product-inventory:reserve";

    @PostMapping("/skus/query")
    BaseResponse<List<SkuSnapshot>> querySkus(@RequestBody SkuQueryRequest request);

    @PostMapping("/inventory/reserve")
    BaseResponse<Void> reserve(@RequestBody ReserveRequest request);
}
