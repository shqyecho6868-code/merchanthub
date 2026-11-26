package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Local message table (table order_outbox), see V2__order_outbox.sql */
@Data
@TableName("order_outbox")
public class OrderOutbox {

    public static final int PENDING = 0;
    public static final int SENT = 1;
    public static final int FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long eventId;
    private String bizKey;
    private String topic;
    private String tag;
    private String payload;
    /** Scheduled delivery time (UTC); NULL = deliver immediately. Used by timeout-close messages (V4) */
    private LocalDateTime deliverAt;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime sentAt;
    /** traceId of the request that wrote this row (V6); put back into message properties on delivery so the trace continues from the order request to the consumer */
    private String traceId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
