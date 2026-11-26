package com.merchanthub.product.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SKU (table sku)
 */
@Data
@TableName("sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private String skuCode;
    /** Spec JSON, e.g. {"color":"white","size":"L"} */
    private String specJson;
    /** Selling price (cents) */
    private Long price;
    /** 0 off sale / 1 on sale */
    private Integer status;

    private Long tenantId;
    /** Maintained by the database default; a null on insert is skipped by MyBatis-Plus */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
