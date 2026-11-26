package com.merchanthub.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Consumer dedup record (table consumed_event) */
@Data
@TableName("consumed_event")
public class ConsumedEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String consumerGroup;
    private Long eventId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
