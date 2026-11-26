package com.merchanthub.user.account.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Account (table account): buyers, merchant admins / staff, platform admins */
@Data
@TableName("account")
public class Account {

    public static final int ENABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Required for merchant roles; null for buyers / platform admins */
    private Long tenantId;
    private String username;
    private String passwordHash;
    /** Name of a {@link com.merchanthub.common.tenant.Role} */
    private String role;
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
