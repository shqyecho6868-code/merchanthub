package com.merchanthub.product.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Inventory (table inventory)
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;
    /** Available quantity */
    private Integer available;
    /** Reserved quantity (ordered but unpaid) */
    private Integer reserved;

    private Long tenantId;
    /** Maintained by the database default; a null on insert is skipped by MyBatis-Plus */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
