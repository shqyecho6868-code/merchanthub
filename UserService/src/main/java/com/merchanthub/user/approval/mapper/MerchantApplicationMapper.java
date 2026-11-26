package com.merchanthub.user.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.user.approval.model.entity.MerchantApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Application status may only flow 10 in review -> 20 rejected -> 10 (resubmit) / 40 (withdrawn), and 10 -> 30 (approved).
 * Every change carries {@code WHERE status = old status} (CAS): if two admins handle the same application at once, or it's executed twice, only one succeeds.
 */
@Mapper
public interface MerchantApplicationMapper extends BaseMapper<MerchantApplication> {

    @Update("UPDATE merchant_application SET status = 30, tenant_id = #{tenantId}, reject_reason = NULL"
            + " WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markApproved(@Param("id") long id, @Param("tenantId") long tenantId);

    @Update("UPDATE merchant_application SET status = 20, reject_reason = #{reason}"
            + " WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markRejected(@Param("id") long id, @Param("reason") String reason);

    /** Resubmit: only the applicant themself, and only in the "rejected" status; submit count +1 */
    @Update("UPDATE merchant_application SET status = 10, shop_name = #{shopName}, admin_username = #{adminUsername},"
            + " admin_password_hash = #{passwordHash}, reject_reason = NULL, submit_count = submit_count + 1"
            + " WHERE id = #{id} AND applicant_user_id = #{applicant} AND status = 20 AND deleted = 0")
    int resubmit(@Param("id") long id, @Param("applicant") long applicant, @Param("shopName") String shopName,
                 @Param("adminUsername") String adminUsername, @Param("passwordHash") String passwordHash);

    @Update("UPDATE merchant_application SET status = 40"
            + " WHERE id = #{id} AND applicant_user_id = #{applicant} AND status = 20 AND deleted = 0")
    int withdraw(@Param("id") long id, @Param("applicant") long applicant);
}
