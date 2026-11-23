package com.merchanthub.user.account.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账号（表 account）：买家、商家管理员 / 员工、平台管理员 */
@Data
@TableName("account")
public class Account {

    public static final int ENABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商家角色必填；买家 / 平台管理员为 null */
    private Long tenantId;
    private String username;
    private String passwordHash;
    /** {@link com.merchanthub.common.tenant.Role} 的名字 */
    private String role;
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
