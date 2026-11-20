package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 商家新订单通知（表 merchant_order_notice） */
@Data
@TableName("merchant_order_notice")
public class MerchantOrderNotice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long orderNo;
    private Long buyerId;
    private Long payAmount;
    private Long eventId;
    private Integer readStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
