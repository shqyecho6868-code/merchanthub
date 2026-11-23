package com.merchanthub.product.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品 / SPU（表 product）
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private String mainImage;
    /** 0 草稿 / 1 审核中 / 2 已上架 / 3 已下架 / 4 审核驳回 */
    private Integer status;
    /** D1：第几次提交上架审核 */
    private Integer auditRound;
    /** D1：本轮审批的流程实例 ID；为空 = 还没发起成功 */
    private String auditInstanceId;
    private LocalDateTime auditSubmittedAt;
    private String rejectReason;

    private Long tenantId;
    /** 由数据库默认值维护，插入时为 null 会被 MyBatis-Plus 跳过 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
