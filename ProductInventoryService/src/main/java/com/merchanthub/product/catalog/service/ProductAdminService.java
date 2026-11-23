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
 * 商家后台的商品读写（C1）。和 order 的商家查询一样，代码里不写 tenant_id，靠租户插件自动拼：
 * 商家 7 改商家 8 的 SKU 价格，UPDATE 变成 {@code WHERE id = ? AND tenant_id = 7}，影响 0 行 → 返回"不存在"。
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
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        List<ProductDetailVO.SkuItem> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getProductId, productId).orderByAsc(Sku::getId))
                .stream()
                .map(s -> new ProductDetailVO.SkuItem(s.getId(), s.getSkuCode(), s.getSpecJson(), s.getPrice(), s.getStatus()))
                .toList();
        return new ProductDetailVO(p.getId(), p.getTenantId(), p.getName(), p.getStatus(), skus);
    }

    /** 改 SKU 售价。已下的订单不受影响：订单里存的是交易快照（B1） */
    public void updatePrice(long skuId, long price) {
        int rows = skuMapper.update(null, new LambdaUpdateWrapper<Sku>().eq(Sku::getId, skuId).set(Sku::getPrice, price));
        if (rows != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        log.info("SKU 改价 skuId={} price={}", skuId, price);
    }
}
