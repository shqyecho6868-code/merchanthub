package com.merchanthub.user.job;

import com.merchanthub.user.approval.mq.ApprovalOutboxRelay;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** user-tenant 的 XXL-Job 任务入口（E3） */
@Component
@RequiredArgsConstructor
public class UserJobs {

    private final ApprovalOutboxRelay relay;

    /** 审批结果本地消息表补偿投递（原 @Scheduled 5 秒一次） */
    @XxlJob("approvalOutboxRelayJob")
    public void approvalOutboxRelay() {
        XxlJobHelper.handleSuccess("到期待发送 " + relay.scan() + " 条");
    }
}
