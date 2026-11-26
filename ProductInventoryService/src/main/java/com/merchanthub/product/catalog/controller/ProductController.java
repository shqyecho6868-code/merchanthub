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
 * Merchant back-office product APIs (C1). Role split:
 * <ul>
 *   <li>View: merchant admin, merchant staff, platform admin</li>
 *   <li>Submit for listing review (D1): both merchant admin and staff can submit; approval is a first review by the merchant admin + a second review by the platform admin</li>
 *   <li>Main image upload (G-上): merchant admin, merchant staff; first request an S3 direct-upload URL, then confirm the binding after uploading</li>
 *   <li>Price change: merchant admin only — staff get 403 (the platform admin can't change prices for a merchant either: the platform governs rules, not a merchant's business decisions)</li>
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
