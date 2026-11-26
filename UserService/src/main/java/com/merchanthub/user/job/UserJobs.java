package com.merchanthub.user.job;

import com.merchanthub.user.approval.mq.ApprovalOutboxRelay;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** user-tenant's XXL-Job job entry points */
@Component
@RequiredArgsConstructor
public class UserJobs {

    private final ApprovalOutboxRelay relay;

    /** Compensating delivery for the approval-result local outbox table (formerly @Scheduled every 5 seconds) */
    @XxlJob("approvalOutboxRelayJob")
    public void approvalOutboxRelay() {
        XxlJobHelper.handleSuccess("Due for sending: " + relay.scan());
    }
}
