package com.merchanthub.product.catalog.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.product.catalog.model.dto.SkuQueryRequest;
import com.merchanthub.product.catalog.model.vo.SkuSnapshotVO;
import com.merchanthub.product.catalog.service.SkuQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal API (/internal/**): only for other services via Feign; the gateway doesn't forward this prefix (configured during the gateway rework).
 * POST because we pass a list of IDs, which is more robust in the request body than in URL parameters.
 */
@RestController
@RequestMapping("/internal/skus")
@RequiredArgsConstructor
public class InternalSkuController {

    private final SkuQueryService skuQueryService;

    @PostMapping("/query")
    public BaseResponse<List<SkuSnapshotVO>> query(@RequestBody @Valid SkuQueryRequest request) {
        return ResultUtils.success(skuQueryService.querySnapshots(request.getSkuIds()));
    }
}
