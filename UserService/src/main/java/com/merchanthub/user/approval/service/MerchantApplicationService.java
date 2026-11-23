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
 * 商家入驻申请（申请人侧）：提交、驳回后修改重提、撤回、查看。审核侧走通用的 {@link ApprovalTaskService}。
 * 每个方法都是一个本地事务：申请表的变更和引擎的推进（启动实例 / 完成"修改重提"任务）一起提交或回滚。
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

        // 业务键把流程实例和申请单关联起来；applicantUserId 存成字符串，BPMN 里直接当 assignee 用
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(FlowVars.PROCESS_ONBOARDING, "onboarding:" + app.getId(),
                Map.of(FlowVars.APPLICATION_ID, app.getId(),
                        FlowVars.APPLICANT, String.valueOf(applicant),
                        FlowVars.SHOP_NAME, req.shopName()));
        app.setProcessInstanceId(pi.getId());
        applicationMapper.updateById(app);
        log.info("入驻申请提交 applicationId={} applicant={} pid={}", app.getId(), applicant, pi.getId());
        return toVO(applicationMapper.selectById(app.getId()));
    }

    /** 驳回后修改重提：同一个流程实例，完成"申请人修改"任务后回到平台审核 */
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
        // 店铺名可能改了，同步到流程变量：平台管理员待办列表里看到的是新名字
        complete(task, Map.of(FlowVars.RESUBMIT, true, FlowVars.SHOP_NAME, req.shopName()));
        log.info("入驻申请重提 applicationId={} applicant={}", id, applicant);
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
        log.info("入驻申请撤回 applicationId={} applicant={}", id, applicant);
        return toVO(applicationMapper.selectById(id));
    }

    /** 申请人只能看自己的；平台管理员都能看 */
    public ApplicationVO get(long id) {
        TenantInfo me = TenantContext.get();
        MerchantApplication app = applicationMapper.selectById(id);
        if (app == null || (me.role() != Role.PLATFORM_ADMIN && !app.getApplicantUserId().equals(me.userId()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "申请不存在");
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
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "申请不存在");
        }
        return app;
    }

    /** 只有"已驳回"时才有这条任务；办理人必须是申请人本人（BPMN 里 assignee = applicantUserId） */
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

    /** 快速失败；最终以 account.username 唯一索引为准（审核通过时才真正建账号） */
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
