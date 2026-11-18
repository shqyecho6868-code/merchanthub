package com.merchanthub.product.catalog.model.vo;

import lombok.Data;

/**
 * 下单需要的 SKU 信息（SKU + 所属商品），供 order 生成交易快照和计价。
 * 一次查询把快照需要的字段都带回去，order 不用再回查商品详情。
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
    /** 售价（分） */
    private Long price;
    /** 0 停售 / 1 在售 */
    private Integer skuStatus;
    /** 2 = 已上架 */
    private Integer productStatus;
}
