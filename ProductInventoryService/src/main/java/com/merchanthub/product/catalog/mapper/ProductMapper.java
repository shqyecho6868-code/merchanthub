package com.merchanthub.product.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Every listing-approval status change is a CAS ({@code WHERE status = old status}). The tenant plugin also appends {@code AND tenant_id = current merchant}:
 * merchant 7 submitting merchant 8's product affects 0 rows.
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** Submit for review: 0 / 4 → 1, start a new round (round +1), clear the previous round's instance and rejection reason */
    @Update("UPDATE product SET status = 1, audit_round = audit_round + 1, audit_instance_id = NULL,"
            + " reject_reason = NULL, audit_submitted_at = NOW(3)"
            + " WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int markSubmitted(@Param("id") long id, @Param("fromStatus") int fromStatus);

    /** Backfill the instance ID after a successful start. Only when "still this round, still under review, not yet filled" */
    @Update("UPDATE product SET audit_instance_id = #{pid}"
            + " WHERE id = #{id} AND status = 1 AND audit_round = #{round} AND audit_instance_id IS NULL AND deleted = 0")
    int bindInstance(@Param("id") long id, @Param("round") int round, @Param("pid") String processInstanceId);

    /**
     * Write back the approval result: 1 → 2 / 4. This single SQL is both the <b>idempotency gate</b> and the <b>out-of-order gate</b>:
     * <ul>
     *   <li>{@code status = 1}: the same result message delivered twice — the second time the status is already 2, 0 rows affected, nothing changes</li>
     *   <li>{@code audit_round = #{round}}: round 1 was rejected and the merchant fixed it and submitted round 2; a late round-1 message doesn't match the round and can't touch round 2</li>
     * </ul>
     */
    @Update("UPDATE product SET status = #{toStatus}, reject_reason = #{reason}, audit_instance_id = #{pid}"
            + " WHERE id = #{id} AND status = 1 AND audit_round = #{round} AND deleted = 0")
    int applyAuditResult(@Param("id") long id, @Param("round") int round, @Param("toStatus") int toStatus,
                         @Param("reason") String reason, @Param("pid") String processInstanceId);

    /**
     * Compensation scan: products under review with no result for more than olderThanSeconds since submission (across merchants, run as SYSTEM), using index (status, audit_submitted_at).
     * Time uses the database's NOW(3) (connection time zone pinned to UTC) — the same clock as on write, unaffected by app server clock skew
     */
    @Select("SELECT * FROM product WHERE status = 1 AND deleted = 0"
            + " AND audit_submitted_at <= NOW(3) - INTERVAL #{olderThanSeconds} SECOND"
            + " ORDER BY audit_submitted_at LIMIT #{limit}")
    List<Product> selectStaleInReview(@Param("olderThanSeconds") long olderThanSeconds, @Param("limit") int limit);
}
