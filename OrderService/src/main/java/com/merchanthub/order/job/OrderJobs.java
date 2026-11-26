package com.merchanthub.order.job;

import com.merchanthub.order.mq.outbox.OutboxRelay;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * order's XXL-Job task entry points. This class is only a "scheduling adapter": it calls the business classes and writes results to the job log;
 * business logic lives in the called classes, which tests call directly without an admin server.
 * <p>
 * Why switch from @Scheduled to XXL-Job: @Scheduled runs on every instance, so multiple instances can only avoid duplicates via SQL claiming;
 * changing frequency needs a release, executions aren't visible, and there's no manual trigger. XXL-Job's admin picks a single instance per routing strategy,
 * and its console can change Cron, view logs, trigger manually, and alert on failure.
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
        XxlJobHelper.handleSuccess("Due for send: " + outboxRelay.scan());
    }

    /** Archive sent messages (suggested daily in the early morning) */
    @XxlJob("orderOutboxArchiveJob")
    public void outboxArchive() {
        XxlJobHelper.handleSuccess("Archived: " + outboxArchiver.archive());
    }

    /** Fallback close of timed-out orders (suggested every minute) */
    @XxlJob("orderTimeoutScanJob")
    public void timeoutScan() {
        XxlJobHelper.handleSuccess("Fallback-closed orders: " + timeoutScanner.scan());
    }

    /** order-side reconciliation: mark pay_anomaly as pending refund + outbox failure alerts (suggested every 5 minutes) */
    @XxlJob("orderReconJob")
    public void recon() {
        OrderReconciler.Result r = reconciler.reconcile();
        XxlJobHelper.handleSuccess("Payment anomalies marked pending refund: " + r.anomaliesMarked() + ", outbox failure alerts: " + r.outboxFailedAlerted());
    }
}
