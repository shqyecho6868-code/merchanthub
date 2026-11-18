package com.merchanthub.order.client;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.client.dto.SkuQueryRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 调用 product-inventory 的内部接口。超时在 application.yml 里配（连接 1s / 读 3s），默认不重试。
 * 下游返回的是统一返回体：HTTP 200 + body.code，业务失败要看 code，见 {@link RemoteResults}。
 */
@FeignClient(name = "product-inventory", contextId = "productInventoryClient", path = "/internal")
public interface ProductInventoryClient {

    @PostMapping("/skus/query")
    BaseResponse<List<SkuSnapshot>> querySkus(@RequestBody SkuQueryRequest request);

    @PostMapping("/inventory/reserve")
    BaseResponse<Void> reserve(@RequestBody ReserveRequest request);
}
