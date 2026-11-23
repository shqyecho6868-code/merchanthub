package com.merchanthub.user.approval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审批结果本地消息表（表 approval_outbox），结构同 order_outbox（B2） */
@Data
@TableName("approval_outbox")
public class ApprovalOutbox {

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
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime sentAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
