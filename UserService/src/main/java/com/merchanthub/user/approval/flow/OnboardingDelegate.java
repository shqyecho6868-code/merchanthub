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
 * The two ServiceTasks of the onboarding process ({@code ${onboardingDelegate.approve(execution)}} in the BPMN).
 * <p>
 * Onboarding approval is a <b>local closed loop</b>: the engine, tenant table and account table are in the same DB, so "complete approval task + create tenant + open account + update application status" is one local transaction,
 * with no MQ needed (contrast with product listing: product is another service and can only be written back via messages, D4). If any step fails (e.g. the username is taken),
 * the whole transaction rolls back and the review task stays in the platform admin's queue.
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
        // source_application_id is unique: one application can only ever create one tenant
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
            // Checked at submit time, but during review someone else may have registered it / another application got approved first; the unique index is the final arbiter
            throw new BusinessException(ErrorCode.USERNAME_TAKEN, "登录名 " + app.getAdminUsername() + " 已被占用，请驳回让申请人修改");
        }

        // CAS: 10 in review -> 30 approved. When two admins click "approve" at once, Flowable's optimistic lock stops one first; this is the second line of defense
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
