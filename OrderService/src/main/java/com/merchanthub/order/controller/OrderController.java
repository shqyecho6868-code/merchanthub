package com.merchanthub.order.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.model.vo.CreateOrderVO;
import com.merchanthub.order.model.vo.OrderDetailVO;
import com.merchanthub.order.ratelimit.OrderRateLimiter;
import com.merchanthub.order.service.OrderCreateService;
import com.merchanthub.order.service.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.merchanthub.order.search.OrderSearchDoc;
import com.merchanthub.order.search.OrderSearchService;
import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;
    private final OrderRateLimiter orderRateLimiter;
    private final OrderSearchService orderSearchService;

    /**
     * Place an order: buyers only. The buyer ID comes from the identity injected by the gateway after parsing the token; no user fields in the request body are trusted.
     * C2: rate-limit per merchant first; over the threshold return 429 immediately without going further (whether tenantId matches SKU ownership is checked in the service)
     */
    @PostMapping
    @RequireRole(Role.BUYER)
    public BaseResponse<CreateOrderVO> create(@RequestBody @Valid CreateOrderRequest request) {
        long buyerId = currentUserId();
        return ResultUtils.success(orderRateLimiter.guard(request.getTenantId(),
                () -> orderCreateService.createOrder(buyerId, request)));
    }

    /** Merchant (admins and staff alike) / platform order detail: the tenant_id condition is added automatically by the tenant plugin, see OrderQueryService */
    @GetMapping("/{orderNo}")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF, Role.PLATFORM_ADMIN})
    public BaseResponse<OrderDetailVO> getForMerchant(@PathVariable long orderNo) {
        return ResultUtils.success(orderQueryService.getForMerchant(orderNo));
    }

    /**
     * E2: merchants search orders by keyword (product name / spec / order number) against the ES replica, with second-level lag.
     * The merchant ID comes only from the gateway-injected identity (not accepted as a parameter) and is a mandatory filter in the ES query
     */
    @GetMapping("/search")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF})
    public BaseResponse<List<OrderSearchDoc>> search(@RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ResultUtils.success(orderSearchService.search(TenantContext.requireTenantId(), keyword, page,
                Math.min(Math.max(size, 1), 100)));
    }

    /** Buyers query their own orders: filtered by buyer_id */
    @GetMapping("/mine/{orderNo}")
    @RequireRole(Role.BUYER)
    public BaseResponse<OrderDetailVO> getForBuyer(@PathVariable long orderNo) {
        return ResultUtils.success(orderQueryService.getForBuyer(currentUserId(), orderNo));
    }

    private static long currentUserId() {
        // @RequireRole(BUYER) already guarantees a context; the gateway always injects X-User-Id for user tokens
        Long userId = TenantContext.get().userId();
        if (userId == null) {
            throw new UnauthorizedException("缺少用户身份");
        }
        return userId;
    }
}
