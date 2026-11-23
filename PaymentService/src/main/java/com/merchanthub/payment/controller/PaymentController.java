package com.merchanthub.payment.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.payment.model.dto.CreatePaymentRequest;
import com.merchanthub.payment.model.dto.MockCallbackRequest;
import com.merchanthub.payment.model.dto.PaymentVO;
import com.merchanthub.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 只有买家能"去支付"；买家 ID 取网关注入的身份，再由 PaymentService 校验订单确实是他的 */
    @PostMapping
    @RequireRole(Role.BUYER)
    public BaseResponse<PaymentVO> create(@RequestBody @Valid CreatePaymentRequest request) {
        Long buyerId = TenantContext.get().userId();
        if (buyerId == null) {
            throw new UnauthorizedException("缺少用户身份");
        }
        return ResultUtils.success(paymentService.create(buyerId, request.orderNo()));
    }

    /**
     * 模拟渠道异步通知。真实渠道：验签 → 处理 → 回固定字符串（如 "success"），回别的渠道就会按退避策略重试通知。
     * 这里返回处理结果（PAID / DUPLICATE / IGNORED）便于观察；三者对渠道而言都是"已收到"。
     */
    @PostMapping("/callback/mock")
    public BaseResponse<String> mockCallback(@RequestBody @Valid MockCallbackRequest request) {
        // 渠道通知没有用户身份（网关白名单放行），不属于任何"登录用户"：以 SYSTEM 身份按支付单号处理。
        // 不设上下文的话，查 payment 表时租户插件会直接报错（fail-closed）
        return ResultUtils.success(TenantContext.callAsSystem(() -> paymentService.handleMockCallback(request)).name());
    }
}
