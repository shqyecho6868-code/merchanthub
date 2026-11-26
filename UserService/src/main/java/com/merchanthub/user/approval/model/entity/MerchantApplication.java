package com.merchanthub.user.approval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Merchant onboarding application (table merchant_application) */
@Data
@TableName("merchant_application")
public class MerchantApplication {

    public static final int SUBMITTED = 10;
    public static final int REJECTED = 20;
    public static final int APPROVED = 30;
    public static final int WITHDRAWN = 40;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long applicantUserId;
    private String shopName;
    private String adminUsername;
    private String adminPasswordHash;
    private Integer status;
    private String rejectReason;
    private Integer submitCount;
    private String processInstanceId;
    private Long tenantId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
