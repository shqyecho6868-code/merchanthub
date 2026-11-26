package com.merchanthub.user.approval.flow;

import org.flowable.common.engine.api.variable.VariableContainer;

/** Process variable names (shared by ${...} in the BPMN and Java code, kept in one place to avoid typos) */
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

    /** The approver's decision: overwritten with each approval step's result on completion, and read by the gateway right after */
    public static final String APPROVED = "approved";
    public static final String COMMENT = "comment";
    public static final String RESUBMIT = "resubmit";

    private FlowVars() {
    }

    public static long longVar(VariableContainer c, String name) {
        Object v = c.getVariable(name);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("Process variable " + name + " is missing or has the wrong type: " + v);
        }
        return n.longValue();
    }
}
