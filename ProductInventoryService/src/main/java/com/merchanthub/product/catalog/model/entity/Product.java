package com.merchanthub.product.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Product / SPU (table product)
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private String mainImage;
    /** 0 draft / 1 under review / 2 listed / 3 delisted / 4 rejected */
    private Integer status;
    /** D1: which listing-review submission this is */
    private Integer auditRound;
    /** D1: process instance ID of this round's approval; null = not started successfully yet */
    private String auditInstanceId;
    private LocalDateTime auditSubmittedAt;
    private String rejectReason;

    private Long tenantId;
    /** Maintained by the database default; a null on insert is skipped by MyBatis-Plus */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
