package com.merchanthub.payment.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** XXL-Job entry point for payment (E3); the logic lives in {@link PaymentReconciler} */
@Component
@RequiredArgsConstructor
public class PaymentJobs {

    private final PaymentReconciler reconciler;

    /** Payment ↔ order reconciliation (suggested every 5 minutes) */
    @XxlJob("paymentReconJob")
    public void recon() {
        PaymentReconciler.Result r = reconciler.reconcile();
        XxlJobHelper.handleSuccess("一致 " + r.consistent() + "，补发 " + r.resent() + "，告警 " + r.alerted()
                + "，关闭遗留支付单 " + r.closedPayments());
    }
}
