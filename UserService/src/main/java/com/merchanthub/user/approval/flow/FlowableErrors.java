package com.merchanthub.user.approval.flow;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;

/**
 * Translate engine exceptions into business errors. A BusinessException thrown in a ServiceTask / listener gets wrapped by Flowable; unwrap it here,
 * otherwise the caller only sees a generic "internal system error".
 */
public final class FlowableErrors {

    private FlowableErrors() {
    }

    public static RuntimeException translate(FlowableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BusinessException be) {
                return be;
            }
        }
        // Two approvers click "approve" at once: the later one's REV_ doesn't match when deleting the task (optimistic lock), or the task is already gone
        if (e instanceof FlowableOptimisticLockingException || e instanceof FlowableObjectNotFoundException) {
            return new BusinessException(ErrorCode.APPROVAL_TASK_CONFLICT);
        }
        return e;
    }
}
