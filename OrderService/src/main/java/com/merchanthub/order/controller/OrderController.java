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

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;

    /** 下单：只有买家能下。买家 ID 取自网关解析 token 后注入的身份，不信任请求体里的任何用户字段 */
    @PostMapping
    @RequireRole(Role.BUYER)
    public BaseResponse<CreateOrderVO> create(@RequestBody @Valid CreateOrderRequest request) {
        return ResultUtils.success(orderCreateService.createOrder(currentUserId(), request));
    }

    /** 商家（管理员、员工都能看）/ 平台查订单详情：tenant_id 条件由租户插件自动拼，见 OrderQueryService */
    @GetMapping("/{orderNo}")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF, Role.PLATFORM_ADMIN})
    public BaseResponse<OrderDetailVO> getForMerchant(@PathVariable long orderNo) {
        return ResultUtils.success(orderQueryService.getForMerchant(orderNo));
    }

    /** 买家查自己的订单：按 buyer_id 过滤 */
    @GetMapping("/mine/{orderNo}")
    @RequireRole(Role.BUYER)
    public BaseResponse<OrderDetailVO> getForBuyer(@PathVariable long orderNo) {
        return ResultUtils.success(orderQueryService.getForBuyer(currentUserId(), orderNo));
    }

    private static long currentUserId() {
        // @RequireRole(BUYER) 已保证有上下文；网关对用户 token 一定注入 X-User-Id
        Long userId = TenantContext.get().userId();
        if (userId == null) {
            throw new UnauthorizedException("缺少用户身份");
        }
        return userId;
    }
}
