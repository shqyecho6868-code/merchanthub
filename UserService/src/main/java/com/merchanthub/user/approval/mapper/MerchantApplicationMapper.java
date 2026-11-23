package com.merchanthub.user.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.user.approval.model.entity.MerchantApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 申请状态只能按 10 审核中 → 20 已驳回 → 10（重提）/ 40（撤回），10 → 30（通过）流转。
 * 每个变更都带 {@code WHERE status = 旧状态}（CAS）：同一张申请被两个管理员同时处理、或重复执行，只有一个能改成功。
 */
@Mapper
public interface MerchantApplicationMapper extends BaseMapper<MerchantApplication> {

    @Update("UPDATE merchant_application SET status = 30, tenant_id = #{tenantId}, reject_reason = NULL"
            + " WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markApproved(@Param("id") long id, @Param("tenantId") long tenantId);

    @Update("UPDATE merchant_application SET status = 20, reject_reason = #{reason}"
            + " WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markRejected(@Param("id") long id, @Param("reason") String reason);

    /** 重提：只有申请人本人、只有"已驳回"状态能改；提交次数 +1 */
    @Update("UPDATE merchant_application SET status = 10, shop_name = #{shopName}, admin_username = #{adminUsername},"
            + " admin_password_hash = #{passwordHash}, reject_reason = NULL, submit_count = submit_count + 1"
            + " WHERE id = #{id} AND applicant_user_id = #{applicant} AND status = 20 AND deleted = 0")
    int resubmit(@Param("id") long id, @Param("applicant") long applicant, @Param("shopName") String shopName,
                 @Param("adminUsername") String adminUsername, @Param("passwordHash") String passwordHash);

    @Update("UPDATE merchant_application SET status = 40"
            + " WHERE id = #{id} AND applicant_user_id = #{applicant} AND status = 20 AND deleted = 0")
    int withdraw(@Param("id") long id, @Param("applicant") long applicant);
}
