package com.merchanthub.product.catalog.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.product.catalog.model.dto.ImageConfirmRequest;
import com.merchanthub.product.catalog.model.dto.ImageUploadRequest;
import com.merchanthub.product.catalog.model.dto.UpdatePriceRequest;
import com.merchanthub.product.catalog.model.vo.ImageUploadVO;
import com.merchanthub.product.catalog.model.vo.ListingSubmitVO;
import com.merchanthub.product.catalog.model.vo.ProductDetailVO;
import com.merchanthub.product.catalog.service.ProductAdminService;
import com.merchanthub.product.catalog.service.ProductImageService;
import com.merchanthub.product.catalog.service.ProductListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家后台商品接口（C1）。角色分工：
 * <ul>
 *   <li>查看：商家管理员、商家员工、平台管理员</li>
 *   <li>提交上架审核（D1）：商家管理员、商家员工都能提交；审批由商家管理员初审 + 平台管理员复审</li>
 *   <li>主图上传（G-上）：商家管理员、商家员工；先申请 S3 直传 URL，上传后再确认绑定</li>
 *   <li>改价：只有商家管理员 —— 员工调用返回 403（平台管理员也不能替商家改价：平台管的是规则，不是商家的经营决策）</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductAdminService productAdminService;
    private final ProductListingService listingService;
    private final ProductImageService imageService;

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

    @PostMapping("/products/{productId}/listing")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF})
    public BaseResponse<ListingSubmitVO> submitListing(@PathVariable long productId) {
        return ResultUtils.success(listingService.submit(productId));
    }

    @PostMapping("/products/{productId}/image/upload-url")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF})
    public BaseResponse<ImageUploadVO> createImageUploadUrl(@PathVariable long productId,
                                                            @RequestBody @Valid ImageUploadRequest request) {
        return ResultUtils.success(imageService.createUploadUrl(productId, request.contentType()));
    }

    @PutMapping("/products/{productId}/image")
    @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF})
    public BaseResponse<String> confirmImage(@PathVariable long productId, @RequestBody @Valid ImageConfirmRequest request) {
        return ResultUtils.success(imageService.confirm(productId, request.objectKey()));
    }
}
