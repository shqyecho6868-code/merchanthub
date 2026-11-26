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

    /** Only buyers can "go pay"; the buyer ID comes from the gateway-injected identity, and PaymentService then verifies the order really belongs to them */
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
     * Simulated async channel notification. A real channel: verify signature → process → reply with a fixed string (e.g. "success"); any other reply makes the channel retry the notification with backoff.
     * Here we return the processing result (PAID / DUPLICATE / IGNORED) for observability; to the channel all three mean "received".
     */
    @PostMapping("/callback/mock")
    public BaseResponse<String> mockCallback(@RequestBody @Valid MockCallbackRequest request) {
        // Channel notifications carry no user identity (whitelisted at the gateway) and don't belong to any "logged-in user": process by payNo as SYSTEM.
        // Without a context, the tenant plugin errors out when querying the payment table (fail-closed)
        return ResultUtils.success(TenantContext.callAsSystem(() -> paymentService.handleMockCallback(request)).name());
    }
}
