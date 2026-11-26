package com.merchanthub.product.catalog.model.vo;

import java.util.List;

/** Product details shown in the merchant back office (C1) */
public record ProductDetailVO(long productId, long tenantId, String name, int status, int auditRound, String rejectReason,
                              List<SkuItem> skus) {

    public record SkuItem(long skuId, String skuCode, String specJson, long price, int status) {
    }
}
