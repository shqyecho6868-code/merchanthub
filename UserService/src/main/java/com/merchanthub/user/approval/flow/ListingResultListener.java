package com.merchanthub.user.approval.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.user.approval.mapper.ListingApprovalMapper;
import com.merchanthub.user.approval.model.entity.ListingApproval;
import com.merchanthub.user.approval.mq.ApprovalOutboxWriter;
import com.merchanthub.user.approval.mq.ListingResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Runs when the product listing process reaches an end event ({@code ${listingResultListener.onEnd(execution, 'APPROVED')}} in the BPMN).
 * <p>
 * This step is where "the approval result reliably becomes business state" begins: it runs in the <b>same DB transaction</b> as "completing the last approval task",
 * and writes two things -- the approval record result (listing_approval, for product's compensation lookup) and a pending message (approval_outbox).
 * If the transaction commits, both exist; if it rolls back, neither does and the task is still pending. There's never "approved but nothing to notify product".
 */
@Slf4j
@Component("listingResultListener")
@RequiredArgsConstructor
public class ListingResultListener {

    private final ListingApprovalMapper approvalMapper;
    private final ApprovalOutboxWriter outboxWriter;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public void onEnd(DelegateExecution execution, String result) {
        long tenantId = FlowVars.longVar(execution, FlowVars.TENANT_ID);
        long productId = FlowVars.longVar(execution, FlowVars.PRODUCT_ID);
        int round = (int) FlowVars.longVar(execution, FlowVars.ROUND);
        String pid = execution.getProcessInstanceId();
        boolean approved = ListingResultEvent.APPROVED.equals(result);
        String reason = approved ? null : (String) execution.getVariable(FlowVars.COMMENT);

        // Who completed the last step varies (merchant admin rejecting at first review / platform admin at second review), so always act as "the merchant that owns this approval record":
        // the SQL includes tenant_id and the message is tagged with this merchant
        TenantContext.runAs(TenantInfo.systemOf(tenantId), () -> {
            int rows = approvalMapper.finish(pid, approved ? ListingApproval.APPROVED : ListingApproval.REJECTED, reason);
            if (rows != 1) {
                // Result already recorded (listener executed twice): don't send a second message
                log.warn("Listing approval already has a result, skipping pid={} productId={} round={}", pid, productId, round);
                return;
            }
            long eventId = idGenerator.nextId();
            ListingResultEvent event = new ListingResultEvent(eventId, tenantId, productId, round, result, reason, pid);
            outboxWriter.append(tenantId, eventId, "listing:" + tenantId + ":" + productId + ":" + round,
                    ListingResultEvent.TOPIC, ListingResultEvent.TAG, toJson(event));
            log.info("Listing approval ended result={} productId={} round={} tenantId={} pid={}", result, productId, round, tenantId, pid);
        });
    }

    private String toJson(ListingResultEvent e) {
        try {
            return objectMapper.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
