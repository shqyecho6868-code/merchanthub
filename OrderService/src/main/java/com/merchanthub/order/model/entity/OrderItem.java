package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Order item (table order_item)
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long productId;
    private Long skuId;
    /** The three fields below are denormalized from the snapshot: shown directly on list pages without calling the product service */
    private String productName;
    private String skuSpec;
    private Long unitPrice;
    private Integer quantity;
    private Long totalAmount;
    private Long discountAmount;
    private Long payAmount;
    private Long snapshotId;

    private Long tenantId;
    /** Maintained by the DB default; null on insert, so MyBatis-Plus skips it */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
