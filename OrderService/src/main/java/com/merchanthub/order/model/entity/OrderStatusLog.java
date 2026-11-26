package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Order status log (insert-only, never updated) (table order_status_log)
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    /** null on creation */
    private Integer fromStatus;
    private Integer toStatus;
    private String event;
    private String operatorType;
    private Long operatorId;
    private String remark;

    private Long tenantId;
    /** Maintained by the DB default; null on insert, so MyBatis-Plus skips it */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
