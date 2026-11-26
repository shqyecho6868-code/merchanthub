package com.merchanthub.user.approval.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.user.approval.model.dto.StartListingRequest;
import com.merchanthub.user.approval.model.vo.ListingResultVO;
import com.merchanthub.user.approval.service.ListingApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal endpoint: only for product via Feign (the gateway doesn't forward /internal/**) */
@RestController
@RequestMapping("/internal/approvals/product-listings")
@RequiredArgsConstructor
public class InternalListingApprovalController {

    private final ListingApprovalService service;

    /** Start listing approval and return the process instance ID. Repeated calls return the same instance (idempotent) */
    @PostMapping
    public BaseResponse<String> start(@RequestBody @Valid StartListingRequest req) {
        try {
            return ResultUtils.success(service.start(req.productId(), req.round(), req.productName()));
        } catch (DuplicateKeyException e) {
            // Concurrent duplicate start: another request inserted the approval record first, so this whole transaction rolled back (including the instance it started); return the one that succeeded first
            return ResultUtils.success(service.existingInstance(req.productId(), req.round()));
        }
    }

    @GetMapping("/{productId}/rounds/{round}")
    public BaseResponse<ListingResultVO> result(@PathVariable long productId, @PathVariable int round) {
        return ResultUtils.success(service.result(productId, round));
    }
}
