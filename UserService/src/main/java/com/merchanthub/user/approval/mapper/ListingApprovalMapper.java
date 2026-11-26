package com.merchanthub.user.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.user.approval.model.entity.ListingApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ListingApprovalMapper extends BaseMapper<ListingApproval> {

    /** Record the result when the process ends: only "in review" can change (CAS), so even if the end listener runs twice it's recorded once and only one message is sent */
    @Update("UPDATE listing_approval SET status = #{status}, reject_reason = #{reason}"
            + " WHERE process_instance_id = #{pid} AND status = 10 AND deleted = 0")
    int finish(@Param("pid") String processInstanceId, @Param("status") int status, @Param("reason") String reason);
}
