package com.merchanthub.product.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存（表 inventory）
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;
    /** 可售数量 */
    private Integer available;
    /** 预占数量（已下单未支付） */
    private Integer reserved;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
