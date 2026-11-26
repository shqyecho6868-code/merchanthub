package com.merchanthub.product.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Product status history (table product_status_log, D1) */
@Data
@TableName("product_status_log")
public class ProductStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private Integer fromStatus;
    private Integer toStatus;
    private String event;
    private Integer auditRound;
    private String source;
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
