package com.merchanthub.order.job;

import com.merchanthub.order.mq.outbox.OutboxRelay;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * order 的 XXL-Job 任务入口（E3）。这里只做"调度适配"：调用对应的业务类、把结果写进调度日志；
 * 业务逻辑都在被调用的类里，测试直接调它们，不需要起调度中心。
 * <p>
 * 为什么从 @Scheduled 换成 XXL-Job：@Scheduled 每个实例都跑，多实例只能靠 SQL 抢占防重复，
 * 而且改频率要发版、执行记录看不到、不能手动触发；XXL-Job 由调度中心按路由策略只选一台执行，
 * 控制台能改 Cron、看日志、手动触发、失败告警（D51）。
 */
@Component
@RequiredArgsConstructor
public class OrderJobs {

    private final OutboxRelay outboxRelay;
    private final OutboxArchiver outboxArchiver;
    private final OrderTimeoutScanner timeoutScanner;
    private final OrderReconciler reconciler;

    /** 本地消息表补偿投递（原 @Scheduled 5 秒一次） */
    @XxlJob("orderOutboxRelayJob")
    public void outboxRelay() {
        XxlJobHelper.handleSuccess("到期待发送 " + outboxRelay.scan() + " 条");
    }

    /** 已发送消息归档（建议每天凌晨一次） */
    @XxlJob("orderOutboxArchiveJob")
    public void outboxArchive() {
        XxlJobHelper.handleSuccess("归档 " + outboxArchiver.archive() + " 条");
    }

    /** 超时订单兜底关单（建议每分钟） */
    @XxlJob("orderTimeoutScanJob")
    public void timeoutScan() {
        XxlJobHelper.handleSuccess("兜底关单 " + timeoutScanner.scan() + " 单");
    }

    /** order 侧对账：pay_anomaly 标记待退款 + outbox 失败告警（建议每 5 分钟） */
    @XxlJob("orderReconJob")
    public void recon() {
        OrderReconciler.Result r = reconciler.reconcile();
        XxlJobHelper.handleSuccess("支付异常标记待退款 " + r.anomaliesMarked() + " 条，outbox 失败告警 " + r.outboxFailedAlerted() + " 条");
    }
}
