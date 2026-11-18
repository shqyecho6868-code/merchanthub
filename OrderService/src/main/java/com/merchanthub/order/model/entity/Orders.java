package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单（表 orders）
 */
@Data
@TableName("orders")
public class Orders {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long buyerId;
    /** 见 OrderStatus */
    private Integer status;
    /** 金额单位一律为分 */
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
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
