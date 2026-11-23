package com.merchanthub.user.approval.flow;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;

/**
 * 把引擎异常翻译成业务错误。ServiceTask / 监听器里抛的 BusinessException 会被 Flowable 包一层，这里解出来，
 * 否则调用方只能看到一个笼统的"系统内部异常"。
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
        // 两个审批人同时点"通过"：后提交的那个删任务时 REV_ 对不上（乐观锁），或者任务已经没了
        if (e instanceof FlowableOptimisticLockingException || e instanceof FlowableObjectNotFoundException) {
            return new BusinessException(ErrorCode.APPROVAL_TASK_CONFLICT);
        }
        return e;
    }
}
