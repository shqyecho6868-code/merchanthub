package com.merchanthub.user.approval.flow;

import org.flowable.common.engine.api.variable.VariableContainer;

/** 流程变量名（BPMN 里的 ${...} 和 Java 代码共用，集中在一处避免拼错） */
public final class FlowVars {

    public static final String PROCESS_LISTING = "productListing";
    public static final String PROCESS_ONBOARDING = "merchantOnboarding";
    public static final String TASK_APPLICANT_REVISE = "applicantRevise";

    public static final String TENANT_ID = "tenantId";
    public static final String PRODUCT_ID = "productId";
    public static final String ROUND = "round";
    public static final String PRODUCT_NAME = "productName";
    public static final String INITIATOR = "initiatorUserId";

    public static final String APPLICATION_ID = "applicationId";
    public static final String APPLICANT = "applicantUserId";
    public static final String SHOP_NAME = "shopName";

    /** 审批人的决定：每个审批节点完成时都会覆盖成这一步的结果，紧跟着的网关读它 */
    public static final String APPROVED = "approved";
    public static final String COMMENT = "comment";
    public static final String RESUBMIT = "resubmit";

    private FlowVars() {
    }

    public static long longVar(VariableContainer c, String name) {
        Object v = c.getVariable(name);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("流程变量 " + name + " 缺失或类型不对: " + v);
        }
        return n.longValue();
    }
}
