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
        XxlJobHelper.handleSuccess("consistent " + r.consistent() + ", re-sent " + r.resent() + ", alerted " + r.alerted()
                + ", stale payments closed " + r.closedPayments());
    }
}
