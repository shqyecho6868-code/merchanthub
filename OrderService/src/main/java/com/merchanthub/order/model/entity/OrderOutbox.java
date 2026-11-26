package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 本地消息表（表 order_outbox），见 V2__order_outbox.sql */
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
    /** 定时投递时间（UTC）；NULL = 立即投递。超时关单消息用（V4） */
    private LocalDateTime deliverAt;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime sentAt;
    /** 写入这行时请求的 traceId（V6）；投递时放回消息属性，链路从下单请求延续到消费者 */
    private String traceId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
