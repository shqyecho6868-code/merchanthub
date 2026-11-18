package com.merchanthub.order.client.dto;

import lombok.Data;

/** product-inventory 返回的 SKU 信息，字段与对方的 SkuSnapshotVO 对应 */
@Data
public class SkuSnapshot {
    public static final int SKU_ON_SALE = 1;
    public static final int PRODUCT_LISTED = 2;

    private Long skuId;
    private Long tenantId;
    private Long productId;
    private String productName;
    private String productDescription;
    private String mainImage;
    private String skuCode;
    private String specJson;
    private Long price;
    private Integer skuStatus;
    private Integer productStatus;

    public boolean isSellable() {
        return Integer.valueOf(SKU_ON_SALE).equals(skuStatus) && Integer.valueOf(PRODUCT_LISTED).equals(productStatus);
    }
}
