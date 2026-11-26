package com.merchanthub.product.catalog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.catalog.mapper.ProductMapper;
import com.merchanthub.product.catalog.mapper.SkuMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.entity.Sku;
import com.merchanthub.product.catalog.model.vo.ProductDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Product reads / writes for the merchant back office. As with order's merchant queries, the code never writes tenant_id; the tenant plugin appends it automatically:
 * when merchant 7 changes the price of merchant 8's SKU, the UPDATE becomes {@code WHERE id = ? AND tenant_id = 7}, affects 0 rows → returns "not found".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAdminService {

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    public ProductDetailVO get(long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Product not found");
        }
        List<ProductDetailVO.SkuItem> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getProductId, productId).orderByAsc(Sku::getId))
                .stream()
                .map(s -> new ProductDetailVO.SkuItem(s.getId(), s.getSkuCode(), s.getSpecJson(), s.getPrice(), s.getStatus()))
                .toList();
        return new ProductDetailVO(p.getId(), p.getTenantId(), p.getName(), p.getStatus(),
                p.getAuditRound() == null ? 0 : p.getAuditRound(), p.getRejectReason(), skus);
    }

    /** Change a SKU's selling price. Existing orders are unaffected: orders store a transaction snapshot */
    public void updatePrice(long skuId, long price) {
        int rows = skuMapper.update(null, new LambdaUpdateWrapper<Sku>().eq(Sku::getId, skuId).set(Sku::getPrice, price));
        if (rows != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Product not found");
        }
        log.info("SKU price changed skuId={} price={}", skuId, price);
    }
}
