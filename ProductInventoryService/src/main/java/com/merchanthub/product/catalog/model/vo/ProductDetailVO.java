package com.merchanthub.product.catalog.model.vo;

import java.util.List;

/** 商家后台看到的商品详情（C1） */
public record ProductDetailVO(long productId, long tenantId, String name, int status, List<SkuItem> skus) {

    public record SkuItem(long skuId, String skuCode, String specJson, long price, int status) {
    }
}
