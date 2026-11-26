package com.merchanthub.product.catalog.client;

import com.merchanthub.common.common.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Calls user-tenant's internal listing-approval API. The merchant identity isn't a parameter: TenantFeignInterceptor propagates the current TenantContext as request headers
 * and user-tenant reads it from there — product can't start approvals for other merchants. Timeouts are in application.yml (contextId = productListingApprovalClient)
 */
@FeignClient(name = "user-tenant", contextId = "productListingApprovalClient", path = "/internal/approvals/product-listings")
public interface ListingApprovalClient {

    /** Start an approval and return the process instance ID; repeated calls for the same (product, round) return the same one (idempotent), so retrying after a timeout is safe */
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
