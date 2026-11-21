package com.merchanthub.payment.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 支付单（表 payment）。订单与支付单分离：一个订单可以有多次支付尝试 */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long payNo;
    private Long orderNo;
    private Long buyerId;
    /** 分 */
    private Long amount;
    private String channel;
    /** 见 PaymentStatus */
    private Integer status;
    private String channelTradeNo;
    private LocalDateTime paidAt;
    /** 赢得成功 CAS 的那次回调的尝试 ID（事务回查用） */
    private String successAttempt;

    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
