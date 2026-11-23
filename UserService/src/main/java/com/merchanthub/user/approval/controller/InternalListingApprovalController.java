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

/** 内部接口：只给 product 通过 Feign 调（网关不转发 /internal/**，C1） */
@RestController
@RequestMapping("/internal/approvals/product-listings")
@RequiredArgsConstructor
public class InternalListingApprovalController {

    private final ListingApprovalService service;

    /** 发起上架审批，返回流程实例 ID。重复调用返回同一个实例（幂等） */
    @PostMapping
    public BaseResponse<String> start(@RequestBody @Valid StartListingRequest req) {
        try {
            return ResultUtils.success(service.start(req.productId(), req.round(), req.productName()));
        } catch (DuplicateKeyException e) {
            // 并发重复发起：另一个请求先插入了审批单，本次事务已整体回滚（包括本次启动的实例），返回先成功的那个
            return ResultUtils.success(service.existingInstance(req.productId(), req.round()));
        }
    }

    @GetMapping("/{productId}/rounds/{round}")
    public BaseResponse<ListingResultVO> result(@PathVariable long productId, @PathVariable int round) {
        return ResultUtils.success(service.result(productId, round));
    }
}
