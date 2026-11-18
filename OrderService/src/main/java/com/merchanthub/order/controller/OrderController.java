package com.merchanthub.order.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.model.vo.CreateOrderVO;
import com.merchanthub.order.service.OrderCreateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    /** 网关鉴权后注入的买家 ID（网关改造前测试时手动带上这个头） */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final OrderCreateService orderCreateService;

    @PostMapping
    public BaseResponse<CreateOrderVO> create(@RequestHeader(USER_ID_HEADER) long buyerId,
                                              @RequestBody @Valid CreateOrderRequest request) {
        return ResultUtils.success(orderCreateService.createOrder(buyerId, request));
    }
}
