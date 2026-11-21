package com.merchanthub.payment.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.payment.model.dto.CreatePaymentRequest;
import com.merchanthub.payment.model.dto.MockCallbackRequest;
import com.merchanthub.payment.model.dto.PaymentVO;
import com.merchanthub.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    /** 网关鉴权后注入的买家 ID */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final PaymentService paymentService;

    @PostMapping
    public BaseResponse<PaymentVO> create(@RequestHeader(USER_ID_HEADER) long buyerId,
                                          @RequestBody @Valid CreatePaymentRequest request) {
        return ResultUtils.success(paymentService.create(buyerId, request.orderNo()));
    }

    /**
     * 模拟渠道异步通知。真实渠道：验签 → 处理 → 回固定字符串（如 "success"），回别的渠道就会按退避策略重试通知。
     * 这里返回处理结果（PAID / DUPLICATE / IGNORED）便于观察；三者对渠道而言都是"已收到"。
     */
    @PostMapping("/callback/mock")
    public BaseResponse<String> mockCallback(@RequestBody @Valid MockCallbackRequest request) {
        return ResultUtils.success(paymentService.handleMockCallback(request).name());
    }
}
