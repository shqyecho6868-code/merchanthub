package com.merchanthub.payment.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** payment 的 XXL-Job 任务入口（E3），逻辑在 {@link PaymentReconciler} */
@Component
@RequiredArgsConstructor
public class PaymentJobs {

    private final PaymentReconciler reconciler;

    /** 支付单 ↔ 订单对账（建议每 5 分钟） */
    @XxlJob("paymentReconJob")
    public void recon() {
        PaymentReconciler.Result r = reconciler.reconcile();
        XxlJobHelper.handleSuccess("一致 " + r.consistent() + "，补发 " + r.resent() + "，告警 " + r.alerted()
                + "，关闭遗留支付单 " + r.closedPayments());
    }
}
