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
 * 商品上架流程走到结束节点时执行（BPMN 里 {@code ${listingResultListener.onEnd(execution, 'APPROVED')}}）。
 * <p>
 * 这一步是"审批结果可靠变成业务状态"的起点：它和"完成最后一个审批任务"在<b>同一个数据库事务</b>里，
 * 写两样东西 —— 审批单结果（listing_approval，供 product 补偿时反查）和一条待发消息（approval_outbox）。
 * 事务提交了，两样都在；回滚了，两样都没有，任务也还在待办里。不会出现"审批通过了但没有任何东西去通知 product"。
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

        // 谁完成的最后一步不确定（商家管理员初审驳回 / 平台管理员复审），这里统一按"这张审批单所属商家"执行：
        // SQL 带上 tenant_id，消息也打上这个商家
        TenantContext.runAs(TenantInfo.systemOf(tenantId), () -> {
            int rows = approvalMapper.finish(pid, approved ? ListingApproval.APPROVED : ListingApproval.REJECTED, reason);
            if (rows != 1) {
                // 已经落过结果（监听器被重复执行）：不再发第二条消息
                log.warn("上架审批单已有结果，跳过 pid={} productId={} round={}", pid, productId, round);
                return;
            }
            long eventId = idGenerator.nextId();
            ListingResultEvent event = new ListingResultEvent(eventId, tenantId, productId, round, result, reason, pid);
            outboxWriter.append(tenantId, eventId, "listing:" + tenantId + ":" + productId + ":" + round,
                    ListingResultEvent.TOPIC, ListingResultEvent.TAG, toJson(event));
            log.info("上架审批结束 result={} productId={} round={} tenantId={} pid={}", result, productId, round, tenantId, pid);
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
