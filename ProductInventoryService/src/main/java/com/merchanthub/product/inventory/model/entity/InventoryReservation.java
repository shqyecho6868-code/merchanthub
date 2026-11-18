package com.merchanthub.product.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存预占记录（表 inventory_reservation）
 */
@Data
@TableName("inventory_reservation")
public class InventoryReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long skuId;
    private Integer quantity;
    /** 见 ReservationStatus */
    private Integer status;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
