package com.merchanthub.user.approval.flow;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.user.account.mapper.AccountMapper;
import com.merchanthub.user.account.mapper.TenantMapper;
import com.merchanthub.user.account.model.entity.Account;
import com.merchanthub.user.account.model.entity.Tenant;
import com.merchanthub.user.approval.mapper.MerchantApplicationMapper;
import com.merchanthub.user.approval.model.entity.MerchantApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 入驻流程的两个 ServiceTask（BPMN 里 {@code ${onboardingDelegate.approve(execution)}}）。
 * <p>
 * 入驻审批是<b>本地闭环</b>：引擎和租户表、账号表在同一个库，"完成审批任务 + 建租户 + 开账号 + 改申请状态"是一个本地事务，
 * 不需要 MQ（对比商品上架：product 在另一个服务，只能靠消息回写，D4）。任何一步失败（比如登录名被占用），
 * 整个事务回滚，审核任务还留在平台管理员的待办里。
 */
@Slf4j
@Component("onboardingDelegate")
@RequiredArgsConstructor
public class OnboardingDelegate {

    private final MerchantApplicationMapper applicationMapper;
    private final TenantMapper tenantMapper;
    private final AccountMapper accountMapper;

    public void approve(DelegateExecution execution) {
        long appId = FlowVars.longVar(execution, FlowVars.APPLICATION_ID);
        MerchantApplication app = applicationMapper.selectById(appId);
        if (app == null || app.getStatus() != MerchantApplication.SUBMITTED) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }

        Tenant tenant = new Tenant();
        tenant.setName(app.getShopName());
        tenant.setStatus(Tenant.OPEN);
        // source_application_id 唯一：同一张申请无论如何只会建出一个租户
        tenant.setSourceApplicationId(appId);
        tenantMapper.insert(tenant);

        Account admin = new Account();
        admin.setTenantId(tenant.getId());
        admin.setUsername(app.getAdminUsername());
        admin.setPasswordHash(app.getAdminPasswordHash());
        admin.setRole(Role.MERCHANT_ADMIN.name());
        admin.setStatus(Account.ENABLED);
        try {
            accountMapper.insert(admin);
        } catch (DuplicateKeyException e) {
            // 提交时检查过，但审核期间可能被别人注册 / 别的申请先通过了；唯一索引是最终裁决
            throw new BusinessException(ErrorCode.USERNAME_TAKEN, "登录名 " + app.getAdminUsername() + " 已被占用，请驳回让申请人修改");
        }

        // CAS：10 审核中 → 30 已通过。两个管理员同时点"通过"时，Flowable 的乐观锁会先挡住一个；这里是第二道
        if (applicationMapper.markApproved(appId, tenant.getId()) != 1) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }
        execution.setVariable(FlowVars.TENANT_ID, tenant.getId());
        log.info("入驻通过：创建租户 tenantId={} 商家管理员 userId={} applicationId={}", tenant.getId(), admin.getId(), appId);
    }

    public void reject(DelegateExecution execution) {
        long appId = FlowVars.longVar(execution, FlowVars.APPLICATION_ID);
        String reason = (String) execution.getVariable(FlowVars.COMMENT);
        if (applicationMapper.markRejected(appId, reason) != 1) {
            throw new BusinessException(ErrorCode.APPLICATION_STATE_INVALID);
        }
        log.info("入驻驳回 applicationId={} reason={}", appId, reason);
    }
}
