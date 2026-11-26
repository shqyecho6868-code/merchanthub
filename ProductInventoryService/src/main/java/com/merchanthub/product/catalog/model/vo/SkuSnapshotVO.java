package com.merchanthub.product.catalog.model.vo;

import lombok.Data;

/**
 * SKU info needed to place an order (SKU + its product), used by order to build the transaction snapshot and price the order.
 * One query brings back every field the snapshot needs, so order doesn't have to look up product details again.
 */
@Data
public class SkuSnapshotVO {
    private Long skuId;
    private Long tenantId;
    private Long productId;
    private String productName;
    private String productDescription;
    private String mainImage;
    private String skuCode;
    private String specJson;
    /** Selling price (cents) */
    private Long price;
    /** 0 off sale / 1 on sale */
    private Integer skuStatus;
    /** 2 = listed */
    private Integer productStatus;
}
