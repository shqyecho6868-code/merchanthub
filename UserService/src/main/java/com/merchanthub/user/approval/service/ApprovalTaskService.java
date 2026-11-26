package com.merchanthub.user.approval.service;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.user.approval.flow.ApprovalGroups;
import com.merchanthub.user.approval.flow.FlowVars;
import com.merchanthub.user.approval.flow.FlowableErrors;
import com.merchanthub.user.approval.model.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Querying and handling tasks (shared by both kinds of approval). Core rule: <b>what you can see = what you can handle</b>, both scoped to the current identity by {@link ApprovalGroups#restrict}.
 * Merchant 8's admin trying to handle merchant 7's taskId -> nothing found under MERCHANT_ADMIN:8 -> treated as nonexistent (63001), not "forbidden".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalTaskService {

    /** Process variables shown in the list: only the ones needed; internal fields like the applicant ID aren't exposed */
    private static final Set<String> SUMMARY_VARS = Set.of(FlowVars.TENANT_ID, FlowVars.PRODUCT_ID, FlowVars.PRODUCT_NAME,
            FlowVars.ROUND, FlowVars.APPLICATION_ID, FlowVars.SHOP_NAME, FlowVars.COMMENT);

    private final TaskService taskService;

    public List<TaskVO> myTasks() {
        TaskQuery q = taskService.createTaskQuery();
        if (!ApprovalGroups.restrict(q, TenantContext.get())) {
            return List.of();
        }
        return q.includeProcessVariables().orderByTaskCreateTime().asc().listPage(0, 100).stream().map(this::toVO).toList();
    }

    /**
     * Approve / reject. After the task completes the engine moves on: onboarding approved -> create tenant and account; last listing step -> write result and pending message,
     * all inside <b>this method's transaction</b> (Flowable joins the current Spring transaction); if any step fails, everything rolls back and the task remains.
     */
    @Transactional
    public void decide(String taskId, boolean approved, String comment) {
        TenantInfo me = TenantContext.get();
        Task task = findMine(taskId, me);
        if (!approved && (comment == null || comment.isBlank())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "A reason is required when rejecting");
        }
        if (FlowVars.TASK_APPLICANT_REVISE.equals(task.getTaskDefinitionKey())) {
            // The applicant's "revise and resubmit" isn't an approval; it goes through the /merchant-applications endpoints (which carry the revised content)
            throw new BusinessException(ErrorCode.APPROVAL_TASK_NOT_FOUND);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(FlowVars.APPROVED, approved);
        vars.put(FlowVars.COMMENT, comment);

        // Let the engine record "who handled it": written to history fields like ACT_HI_COMMENT.USER_ID_; it's a thread-local, so it must be cleared afterwards
        Authentication.setAuthenticatedUserId(String.valueOf(me.userId()));
        try {
            taskService.addComment(taskId, task.getProcessInstanceId(), (approved ? "Approved" : "Rejected")
                    + (comment == null || comment.isBlank() ? "" : ": " + comment));
            taskService.complete(taskId, vars);
        } catch (FlowableException e) {
            throw FlowableErrors.translate(e);
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
        log.info("Approval handled taskId={} task={} approved={} by userId={} role={} tenantId={}",
                taskId, task.getTaskDefinitionKey(), approved, me.userId(), me.role(), me.tenantId());
    }

    /** Look up this task under the current identity; not found (nonexistent / already done / not yours) is always 63001 */
    Task findMine(String taskId, TenantInfo me) {
        TaskQuery q = taskService.createTaskQuery().taskId(taskId);
        Task task = ApprovalGroups.restrict(q, me) ? q.singleResult() : null;
        if (task == null) {
            throw new BusinessException(ErrorCode.APPROVAL_TASK_NOT_FOUND);
        }
        return task;
    }

    private TaskVO toVO(Task t) {
        Map<String, Object> summary = new LinkedHashMap<>();
        t.getProcessVariables().forEach((k, v) -> {
            if (SUMMARY_VARS.contains(k)) {
                summary.put(k, v);
            }
        });
        // processDefinitionId looks like productListing:1:abc; the part before the first colon is the process key
        String processKey = t.getProcessDefinitionId() == null ? null : t.getProcessDefinitionId().split(":")[0];
        return new TaskVO(t.getId(), t.getName(), t.getTaskDefinitionKey(), processKey, t.getProcessInstanceId(),
                t.getCreateTime(), summary);
    }
}
