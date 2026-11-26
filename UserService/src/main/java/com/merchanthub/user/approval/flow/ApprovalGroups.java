package com.merchanthub.user.approval.flow;

import com.merchanthub.common.tenant.TenantInfo;
import org.flowable.task.api.TaskQuery;

/**
 * Approver = "candidate group" (D1): the engine stores no users or roles, just a group-name string; the naming rule is defined here in one place.
 * <ul>
 *   <li>Platform admin -> {@code PLATFORM_ADMIN}</li>
 *   <li>Merchant 7's admin -> {@code MERCHANT_ADMIN:7} (the tenant is encoded in the group name; merchant 8's admin queries MERCHANT_ADMIN:8 and naturally can't find it)</li>
 *   <li>Onboarding applicant -> no group, assignee = their userId directly</li>
 * </ul>
 * Which group the current user belongs to is derived from C1's TenantContext (identity injected by the gateway that clients can't alter); <b>group names from the frontend are never accepted</b>.
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
     * Restrict the task query to "tasks the current identity can handle". Returns false = this identity has no tasks at all (staff, system identity).
     * Both the task list and the handling endpoint go through here: handling also queries with it first and treats not-found as nonexistent -- filtering only the list but not checking on handling is not enough.
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
