package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Order (table orders)
 */
@Data
@TableName("orders")
public class Orders {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long buyerId;
    /** See OrderStatus */
    private Integer status;
    /** All amounts are in cents */
    private Long totalAmount;
    private Long discountAmount;
    private Long payAmount;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime completedAt;
    private LocalDateTime closedAt;
    private String closeReason;

    private Long tenantId;
    /** Maintained by the DB default; null on insert, so MyBatis-Plus skips it */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
