package com.merchanthub.user.approval.flow;

import com.merchanthub.common.tenant.TenantInfo;
import org.flowable.task.api.TaskQuery;

/**
 * 审批人 = "候选组"（D1）：引擎里不存人员、角色，只存一个组名字符串，组名规则在这里一处定义。
 * <ul>
 *   <li>平台管理员 → {@code PLATFORM_ADMIN}</li>
 *   <li>商家 7 的管理员 → {@code MERCHANT_ADMIN:7}（租户编进组名，商家 8 的管理员查的是 MERCHANT_ADMIN:8，天然查不到）</li>
 *   <li>入驻申请人 → 不用组，直接 assignee = 他的 userId</li>
 * </ul>
 * 当前用户属于哪个组由 C1 的 TenantContext（网关注入、客户端改不了的身份）推出来，<b>不接受前端传组名</b>。
 */
public final class ApprovalGroups {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private static final String MERCHANT_ADMIN_PREFIX = "MERCHANT_ADMIN:";

    private ApprovalGroups() {
    }

    public static String merchantAdmin(long tenantId) {
        return MERCHANT_ADMIN_PREFIX + tenantId;
    }

    /**
     * 把任务查询限定在"当前身份能处理的任务"。返回 false = 这个身份没有任何待办（员工、系统身份）。
     * 待办列表和办理接口都走这里：办理时同样先用它查一遍，查不到就当不存在 —— 不能只在列表里过滤、办理时不查。
     */
    public static boolean restrict(TaskQuery query, TenantInfo me) {
        if (me == null) {
            return false;
        }
        switch (me.role()) {
            case PLATFORM_ADMIN -> query.taskCandidateGroup(PLATFORM_ADMIN);
            case MERCHANT_ADMIN -> query.taskCandidateGroup(merchantAdmin(me.tenantId()));
            case BUYER -> {
                if (me.userId() == null) {
                    return false;
                }
                query.taskAssignee(String.valueOf(me.userId()));
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
