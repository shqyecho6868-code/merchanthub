package com.merchanthub.product.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Inventory reservation record (table inventory_reservation)
 */
@Data
@TableName("inventory_reservation")
public class InventoryReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long skuId;
    private Integer quantity;
    /** See ReservationStatus */
    private Integer status;

    private Long tenantId;
    /** Maintained by the database default; a null on insert is skipped by MyBatis-Plus */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
