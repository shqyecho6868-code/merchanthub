package com.merchanthub.user.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.user.approval.model.entity.ListingApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ListingApprovalMapper extends BaseMapper<ListingApproval> {

    /** 流程结束时落结果：只有"审批中"能改（CAS），结束监听器万一被执行两次也只记一次、只发一条消息 */
    @Update("UPDATE listing_approval SET status = #{status}, reject_reason = #{reason}"
            + " WHERE process_instance_id = #{pid} AND status = 10 AND deleted = 0")
    int finish(@Param("pid") String processInstanceId, @Param("status") int status, @Param("reason") String reason);
}
