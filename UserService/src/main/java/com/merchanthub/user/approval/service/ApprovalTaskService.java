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
 * 待办查询与办理（两类审批共用）。核心规则：<b>能看到的 = 能办的</b>，都由 {@link ApprovalGroups#restrict} 按当前身份限定。
 * 商家 8 的管理员拿着商家 7 的 taskId 来办理 → 按 MERCHANT_ADMIN:8 查不到 → 当作不存在（63001），不说"无权"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalTaskService {

    /** 列表里展示的流程变量：只挑需要的，申请人 ID 这类内部字段不外露 */
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
     * 通过 / 驳回。完成任务后引擎会接着往下走：入驻通过 → 建租户开账号；上架最后一步 → 写结果和待发消息，
     * 这些都在<b>本方法的事务</b>里（Flowable 加入当前 Spring 事务），任何一步失败整体回滚、任务还在。
     */
    @Transactional
    public void decide(String taskId, boolean approved, String comment) {
        TenantInfo me = TenantContext.get();
        Task task = findMine(taskId, me);
        if (!approved && (comment == null || comment.isBlank())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "驳回必须填写原因");
        }
        if (FlowVars.TASK_APPLICANT_REVISE.equals(task.getTaskDefinitionKey())) {
            // 申请人的"修改重提"不是审批，走 /merchant-applications 的接口（要带修改后的内容）
            throw new BusinessException(ErrorCode.APPROVAL_TASK_NOT_FOUND);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(FlowVars.APPROVED, approved);
        vars.put(FlowVars.COMMENT, comment);

        // 让引擎记下"谁办的"：写进 ACT_HI_COMMENT.USER_ID_ 等历史字段；线程变量，用完必须清掉
        Authentication.setAuthenticatedUserId(String.valueOf(me.userId()));
        try {
            taskService.addComment(taskId, task.getProcessInstanceId(), (approved ? "通过" : "驳回")
                    + (comment == null || comment.isBlank() ? "" : "：" + comment));
            taskService.complete(taskId, vars);
        } catch (FlowableException e) {
            throw FlowableErrors.translate(e);
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
        log.info("审批办理 taskId={} task={} approved={} by userId={} role={} tenantId={}",
                taskId, task.getTaskDefinitionKey(), approved, me.userId(), me.role(), me.tenantId());
    }

    /** 按当前身份查这条待办；查不到（不存在 / 已办完 / 不是你的）一律 63001 */
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
        // processDefinitionId 形如 productListing:1:abc，冒号前就是流程 key
        String processKey = t.getProcessDefinitionId() == null ? null : t.getProcessDefinitionId().split(":")[0];
        return new TaskVO(t.getId(), t.getName(), t.getTaskDefinitionKey(), processKey, t.getProcessInstanceId(),
                t.getCreateTime(), summary);
    }
}
