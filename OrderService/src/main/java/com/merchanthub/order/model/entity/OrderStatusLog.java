package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态流水（只插入不更新）（表 order_status_log）
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    /** 创建时为 null */
    private Integer fromStatus;
    private Integer toStatus;
    private String event;
    private String operatorType;
    private Long operatorId;
    private String remark;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
