package com.merchanthub.product.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 上架审批的状态变更全部是 CAS（{@code WHERE status = 旧状态}）。租户插件还会自动拼 {@code AND tenant_id = 当前商家}：
 * 商家 7 提交商家 8 的商品，影响 0 行。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 提交审核：0 / 4 → 1，开新的一轮（轮次 +1），清掉上一轮的实例和驳回原因 */
    @Update("UPDATE product SET status = 1, audit_round = audit_round + 1, audit_instance_id = NULL,"
            + " reject_reason = NULL, audit_submitted_at = NOW(3)"
            + " WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int markSubmitted(@Param("id") long id, @Param("fromStatus") int fromStatus);

    /** 发起成功后回填实例 ID。只在"还是这一轮、还在审核中、还没填过"时填 */
    @Update("UPDATE product SET audit_instance_id = #{pid}"
            + " WHERE id = #{id} AND status = 1 AND audit_round = #{round} AND audit_instance_id IS NULL AND deleted = 0")
    int bindInstance(@Param("id") long id, @Param("round") int round, @Param("pid") String processInstanceId);

    /**
     * 回写审批结果：1 → 2 / 4。这一条 SQL 同时是<b>幂等闸门</b>和<b>防乱序闸门</b>：
     * <ul>
     *   <li>{@code status = 1}：同一条结果消息重复投递，第二次时状态已是 2，影响 0 行，什么都不改</li>
     *   <li>{@code audit_round = #{round}}：第 1 轮被驳回、商家改完提交了第 2 轮，第 1 轮的迟到消息对不上轮次，改不了第 2 轮</li>
     * </ul>
     */
    @Update("UPDATE product SET status = #{toStatus}, reject_reason = #{reason}, audit_instance_id = #{pid}"
            + " WHERE id = #{id} AND status = 1 AND audit_round = #{round} AND deleted = 0")
    int applyAuditResult(@Param("id") long id, @Param("round") int round, @Param("toStatus") int toStatus,
                         @Param("reason") String reason, @Param("pid") String processInstanceId);

    /**
     * 补偿扫描：审核中、提交超过 olderThanSeconds 还没结果的商品（跨商家，SYSTEM 身份执行），走索引 (status, audit_submitted_at)。
     * 时间用数据库的 NOW(3)（连接时区固定 UTC），和写入时同一个时钟，不受应用服务器时钟偏差影响
     */
    @Select("SELECT * FROM product WHERE status = 1 AND deleted = 0"
            + " AND audit_submitted_at <= NOW(3) - INTERVAL #{olderThanSeconds} SECOND"
            + " ORDER BY audit_submitted_at LIMIT #{limit}")
    List<Product> selectStaleInReview(@Param("olderThanSeconds") long olderThanSeconds, @Param("limit") int limit);
}
