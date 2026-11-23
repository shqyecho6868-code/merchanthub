package com.merchanthub.product.catalog.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.product.catalog.model.dto.UpdatePriceRequest;
import com.merchanthub.product.catalog.model.vo.ProductDetailVO;
import com.merchanthub.product.catalog.service.ProductAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家后台商品接口（C1）。角色分工：
 * <ul>
 *   <li>查看：商家管理员、商家员工、平台管理员</li>
 *   <li>改价：只有商家管理员 —— 员工调用返回 403（平台管理员也不能替商家改价：平台管的是规则，不是商家的经营决策）</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductAdminService productAdminService;

    @GetMapping("/products/{productId}")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF, Role.PLATFORM_ADMIN})
    public BaseResponse<ProductDetailVO> get(@PathVariable long productId) {
        return ResultUtils.success(productAdminService.get(productId));
    }

    @PutMapping("/skus/{skuId}/price")
    @RequireRole(Role.MERCHANT_ADMIN)
    public BaseResponse<Void> updatePrice(@PathVariable long skuId, @RequestBody @Valid UpdatePriceRequest request) {
        productAdminService.updatePrice(skuId, request.price());
        return ResultUtils.success(null);
    }
}
