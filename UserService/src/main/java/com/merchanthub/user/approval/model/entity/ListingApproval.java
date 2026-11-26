package com.merchanthub.user.approval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Product listing approval record (table listing_approval): one submission = one row = one process instance */
@Data
@TableName("listing_approval")
public class ListingApproval {

    public static final int RUNNING = 10;
    public static final int APPROVED = 30;
    public static final int REJECTED = 40;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private Integer auditRound;
    private String productName;
    private Long initiatorUserId;
    private String processInstanceId;
    private Integer status;
    private String rejectReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
