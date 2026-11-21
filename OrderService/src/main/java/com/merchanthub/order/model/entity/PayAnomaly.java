package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 支付异常（表 pay_anomaly，V4）：支付成功但订单没能变成已支付，留给 E3 对账退款 */
@Data
@TableName("pay_anomaly")
public class PayAnomaly {

    public static final String PAID_AFTER_CLOSED = "PAID_AFTER_CLOSED";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String MISMATCH = "MISMATCH";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long payNo;
    private Long orderNo;
    private Long amount;
    private String type;
    private Integer orderStatus;
    private Integer handleStatus;
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
