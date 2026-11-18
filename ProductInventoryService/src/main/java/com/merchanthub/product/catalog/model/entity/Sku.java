package com.merchanthub.product.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SKU（表 sku）
 */
@Data
@TableName("sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private String skuCode;
    /** 规格 JSON，如 {"颜色":"白","尺码":"L"} */
    private String specJson;
    /** 售价（分） */
    private Long price;
    /** 0 停售 / 1 在售 */
    private Integer status;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
