package com.merchanthub.order.job;

import com.merchanthub.order.mq.outbox.OutboxRelay;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * order's XXL-Job task entry points (E3). This class is only a "scheduling adapter": it calls the business classes and writes results to the job log;
 * business logic lives in the called classes, which tests call directly without an admin server.
 * <p>
 * Why switch from @Scheduled to XXL-Job: @Scheduled runs on every instance, so multiple instances can only avoid duplicates via SQL claiming;
 * changing frequency needs a release, executions aren't visible, and there's no manual trigger. XXL-Job's admin picks a single instance per routing strategy,
 * and its console can change Cron, view logs, trigger manually, and alert on failure (D51).
 */
@Component
@RequiredArgsConstructor
public class OrderJobs {

    private final OutboxRelay outboxRelay;
    private final OutboxArchiver outboxArchiver;
    private final OrderTimeoutScanner timeoutScanner;
    private final OrderReconciler reconciler;

    /** Outbox compensation delivery (formerly @Scheduled every 5s) */
    @XxlJob("orderOutboxRelayJob")
    public void outboxRelay() {
        XxlJobHelper.handleSuccess("到期待发送 " + outboxRelay.scan() + " 条");
    }

    /** Archive sent messages (suggested daily in the early morning) */
    @XxlJob("orderOutboxArchiveJob")
    public void outboxArchive() {
        XxlJobHelper.handleSuccess("归档 " + outboxArchiver.archive() + " 条");
    }

    /** Fallback close of timed-out orders (suggested every minute) */
    @XxlJob("orderTimeoutScanJob")
    public void timeoutScan() {
        XxlJobHelper.handleSuccess("兜底关单 " + timeoutScanner.scan() + " 单");
    }

    /** order-side reconciliation: mark pay_anomaly as pending refund + outbox failure alerts (suggested every 5 minutes) */
    @XxlJob("orderReconJob")
    public void recon() {
        OrderReconciler.Result r = reconciler.reconcile();
        XxlJobHelper.handleSuccess("支付异常标记待退款 " + r.anomaliesMarked() + " 条，outbox 失败告警 " + r.outboxFailedAlerted() + " 条");
    }
}
