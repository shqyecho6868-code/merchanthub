package com.merchanthub.product.catalog.client;

import com.merchanthub.common.common.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调 user-tenant 的上架审批内部接口（D1）。商家身份不在参数里：TenantFeignInterceptor 把当前 TenantContext 透传成请求头，
 * user-tenant 从头里取 —— product 发起不了别的商家的审批。超时见 application.yml（contextId = productListingApprovalClient）
 */
@FeignClient(name = "user-tenant", contextId = "productListingApprovalClient", path = "/internal/approvals/product-listings")
public interface ListingApprovalClient {

    /** 发起审批，返回流程实例 ID；同一 (商品, 轮次) 重复调用返回同一个（幂等），所以超时后可以放心重试 */
    @PostMapping
    BaseResponse<String> start(@RequestBody StartListing request);

    @GetMapping("/{productId}/rounds/{round}")
    BaseResponse<ListingResult> result(@PathVariable("productId") long productId, @PathVariable("round") int round);

    record StartListing(long productId, int round, String productName) {
    }

    /** @param state NOT_FOUND / RUNNING / APPROVED / REJECTED */
    record ListingResult(String state, String processInstanceId, String rejectReason) {

        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String RUNNING = "RUNNING";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";
    }
}
