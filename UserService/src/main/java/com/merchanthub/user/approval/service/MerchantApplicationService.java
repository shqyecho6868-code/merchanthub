package com.merchanthub.user.approval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.user.account.mapper.AccountMapper;
import com.merchanthub.user.account.model.entity.Account;
import com.merchanthub.user.approval.flow.FlowVars;
import com.merchanthub.user.approval.flow.FlowableErrors;
import com.merchanthub.user.approval.mapper.MerchantApplicationMapper;
import com.merchanthub.user.approval.model.dto.ApplicationRequest;
import com.merchanthub.user.approval.model.entity.MerchantApplication;
import com.merchanthub.user.approval.model.vo.ApplicationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Merchant onboarding applications (applicant side): submit, revise and resubmit after rejection, withdraw, view. The reviewer side uses the generic {@link ApprovalTaskService}.
 * Each method is one local transaction: application table changes and engine progress (start instance / complete the "revise and resubmit" task) commit or roll back together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantApplicationService {

    private final MerchantApplicationMapper applicationMapper;
    private final AccountMapper accountMapper;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public ApplicationVO submit(ApplicationRequest req) {
        long applicant = currentUserId();
        Long inProgress = applicationMapper.selectCount(new LambdaQueryWrapper<MerchantApplication>()
                .eq(MerchantApplication::getApplicantUserId, applicant)
                .in(MerchantApplication::getStatus, MerchantApplication.SUBMITTED, MerchantApplication.REJECTED));
        if (inProgress > 0) {
            throw new BusinessException(ErrorCode.APPLICATION_IN_PROGRESS);
        }
        checkUsernameFree(req.adminUsername());

        MerchantApplication app = new MerchantApplication();
        app.setApplicantUserId(applicant);
        app.setShopName(req.shopName());
        app.setAdminUsername(req.adminUsername());
        app.setAdminPasswordHash(passwordEncoder.encode(req.adminPassword()));
        app.setStatus(MerchantApplication.SUBMITTED);
        app.setSubmitCount(1);
        applicationMapper.insert(app);

        // The business key links the process instance to the application; applicantUserId is stored as a string so the BPMN can use it directly as assignee
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(FlowVars.PROCESS_ONBOARDING, "onboarding:" + app.getId(),
                Map.of(FlowVars.APPLICATION_ID, app.getId(),
                        FlowVars.APPLICANT, String.valueOf(applicant),
                        FlowVars.SHOP_NAME, req.shopName()));
        app.setProcessInstanceId(pi.getId());
        applicationMapper.updateById(app);
        log.info("Onboarding application submitted applicationId={} applicant={} pid={}", app.getId(), applicant, pi.getId());
        return toVO(applicationMapper.selectById(app.getId()));
    }

    /** Revise and resubmit after rejection: same process instance; completing the "applicant revises" task returns to platform review */
    @Transactional
    public ApplicationVO resubmit(long id, ApplicationRequest req) {
        long applicant = currentUserId();
        MerchantApplication app = mine(id, applicant);
        Task task = reviseTask(app, applicant);
        checkUsernameFree(req.adminUsername());
        if (applicationMapper.resubmit(id, applicant, req.shopName(), req.adminUsername(),
                passwordEncoder.encode(req.adminPassword())) != 1) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }
        // The shop name may have changed, so sync it to the process variables: the platform admin's task list shows the new name
        complete(task, Map.of(FlowVars.RESUBMIT, true, FlowVars.SHOP_NAME, req.shopName()));
        log.info("Onboarding application resubmitted applicationId={} applicant={}", id, applicant);
        return toVO(applicationMapper.selectById(id));
    }

    @Transactional
    public ApplicationVO withdraw(long id) {
        long applicant = currentUserId();
        MerchantApplication app = mine(id, applicant);
        Task task = reviseTask(app, applicant);
        if (applicationMapper.withdraw(id, applicant) != 1) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }
        complete(task, Map.of(FlowVars.RESUBMIT, false));
        log.info("Onboarding application withdrawn applicationId={} applicant={}", id, applicant);
        return toVO(applicationMapper.selectById(id));
    }

    /** Applicants can only see their own; platform admins can see all */
    public ApplicationVO get(long id) {
        TenantInfo me = TenantContext.get();
        MerchantApplication app = applicationMapper.selectById(id);
        if (app == null || (me.role() != Role.PLATFORM_ADMIN && !app.getApplicantUserId().equals(me.userId()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Application not found");
        }
        return toVO(app);
    }

    public List<ApplicationVO> mineList() {
        return applicationMapper.selectList(new LambdaQueryWrapper<MerchantApplication>()
                        .eq(MerchantApplication::getApplicantUserId, currentUserId())
                        .orderByDesc(MerchantApplication::getId))
                .stream().map(MerchantApplicationService::toVO).toList();
    }

    private MerchantApplication mine(long id, long applicant) {
        MerchantApplication app = applicationMapper.selectById(id);
        if (app == null || !app.getApplicantUserId().equals(applicant)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Application not found");
        }
        return app;
    }

    /** This task exists only while "rejected"; the assignee must be the applicant themself (assignee = applicantUserId in the BPMN) */
    private Task reviseTask(MerchantApplication app, long applicant) {
        Task task = taskService.createTaskQuery().processInstanceId(app.getProcessInstanceId())
                .taskDefinitionKey(FlowVars.TASK_APPLICANT_REVISE).taskAssignee(String.valueOf(applicant)).singleResult();
        if (task == null) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }
        return task;
    }

    private void complete(Task task, Map<String, Object> vars) {
        try {
            taskService.complete(task.getId(), vars);
        } catch (FlowableException e) {
            throw FlowableErrors.translate(e);
        }
    }

    /** Fail fast; the account.username unique index is the final word (the account is only really created on approval) */
    private void checkUsernameFree(String username) {
        if (accountMapper.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username)) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
    }

    private static long currentUserId() {
        TenantInfo me = TenantContext.get();
        if (me == null || me.userId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return me.userId();
    }

    static ApplicationVO toVO(MerchantApplication a) {
        return new ApplicationVO(a.getId(), a.getApplicantUserId(), a.getShopName(), a.getAdminUsername(), a.getStatus(),
                a.getRejectReason(), a.getSubmitCount(), a.getProcessInstanceId(), a.getTenantId());
    }
}
