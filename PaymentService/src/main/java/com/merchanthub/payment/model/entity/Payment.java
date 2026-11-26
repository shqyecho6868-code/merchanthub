package com.merchanthub.payment.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Payment (table payment). Orders and payments are separate: one order can have multiple payment attempts */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long payNo;
    private Long orderNo;
    private Long buyerId;
    /** In cents */
    private Long amount;
    private String channel;
    /** See PaymentStatus */
    private Integer status;
    private String channelTradeNo;
    private LocalDateTime paidAt;
    /** Attempt ID of the callback that won the success CAS (used by the transaction check-back) */
    private String successAttempt;
    /** 0 not reconciled / 1 consistent / 3 re-sends exhausted, alerted */
    private Integer reconStatus;
    private Integer reconRetry;

    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
