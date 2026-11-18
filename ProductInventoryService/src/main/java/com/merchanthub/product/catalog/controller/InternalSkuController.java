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
 * 内部接口（/internal/**）：只给其他服务通过 Feign 调用，网关不转发这个前缀（gateway 改造时配置）。
 * 用 POST 是因为要传一组 ID，放请求体比拼 URL 参数更稳。
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
