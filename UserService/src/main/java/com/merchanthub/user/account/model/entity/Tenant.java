package com.merchanthub.user.account.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Tenant / merchant (table tenant) */
@Data
@TableName("tenant")
public class Tenant {

    public static final int OPEN = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer status;
    private Long sourceApplicationId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
