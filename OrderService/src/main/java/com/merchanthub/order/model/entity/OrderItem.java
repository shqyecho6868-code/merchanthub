package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单明细（表 order_item）
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long productId;
    private Long skuId;
    /** 以下三项是快照冗余：列表页直接展示，不回查商品服务 */
    private String productName;
    private String skuSpec;
    private Long unitPrice;
    private Integer quantity;
    private Long totalAmount;
    private Long discountAmount;
    private Long payAmount;
    private Long snapshotId;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
