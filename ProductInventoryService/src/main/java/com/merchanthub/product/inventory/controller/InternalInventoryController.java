package com.merchanthub.product.inventory.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口：order 下单时调用 */
@RestController
@RequestMapping("/internal/inventory")
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryReservationService reservationService;

    @PostMapping("/reserve")
    public BaseResponse<Void> reserve(@RequestBody @Valid ReserveRequest request) {
        reservationService.reserve(request);
        return ResultUtils.success(null);
    }
}
