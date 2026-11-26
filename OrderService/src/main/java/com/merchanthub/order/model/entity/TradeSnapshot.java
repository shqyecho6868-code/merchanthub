package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trade snapshot (table trade_snapshot)
 */
@Data
@TableName("trade_snapshot")
public class TradeSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderNo;
    private Long skuId;
    /** Full product info at the time of ordering (JSON) */
    private String content;

    private Long tenantId;
    /** Maintained by the DB default; null on insert, so MyBatis-Plus skips it */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
