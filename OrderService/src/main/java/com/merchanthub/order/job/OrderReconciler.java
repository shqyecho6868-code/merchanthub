package com.merchanthub.order.job;

import com.merchanthub.common.recon.ReconAlarm;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.mapper.PayAnomalyMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import com.merchanthub.order.model.entity.PayAnomaly;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * order-side reconciliation: handles two kinds of problems that "can't be auto-fixed, only flagged + alerted".
 * <ol>
 *   <li><b>pay_anomaly</b>: money received, order not paid (paid after close / duplicate payment / amount or merchant mismatch).
 *       <b>Don't modify the order</b>: a closed order's inventory was released long ago and may have been bought by someone else; "reviving" it as paid would oversell or be unfulfillable;
 *       the right fix is a refund (out of scope). Here we set handle_status = 2 (pending refund) + record recon_exception + alert</li>
 *   <li><b>outbox delivery failures (status = 2)</b>: still unsent after max retries; alert, and after manual investigation set back to 0 to redeliver</li>
 * </ol>
 * "Payment succeeded but order still pending" isn't fixed here: payment-side reconciliation detects it and re-sends the payment-success event, which order consumes normally to fix it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconciler {

    private final PayAnomalyMapper anomalyMapper;
    private final OrderOutboxMapper outboxMapper;
    private final ReconAlarm alarm;

    @Value("${merchanthub.order.jobs.batch-size:200}")
    private int batchSize;

    public record Result(int anomaliesMarked, int outboxFailedAlerted) {
    }

    public Result reconcile() {
        return TenantContext.callAsSystem(() -> new Result(handleAnomalies(), alertFailedOutbox()));
    }

    private int handleAnomalies() {
        int marked = 0;
        for (PayAnomaly a : anomalyMapper.selectPending(batchSize)) {
            String type = switch (a.getType()) {
                case PayAnomaly.PAID_AFTER_CLOSED -> ReconAlarm.PAID_AFTER_CLOSED;
                case PayAnomaly.DUPLICATE_PAYMENT -> ReconAlarm.DUPLICATE_PAYMENT;
                default -> ReconAlarm.PAY_MISMATCH;
            };
            alarm.raise(a.getTenantId(), type, "payNo:" + a.getPayNo(),
                    "Pending refund orderNo=" + a.getOrderNo() + " amount=" + a.getAmount() + " orderStatusWhenRecorded=" + a.getOrderStatus()
                            + " remark=" + a.getRemark());
            if (anomalyMapper.markRefundPending(a.getId()) == 1) {
                marked++;
            }
        }
        return marked;
    }

    private int alertFailedOutbox() {
        List<OrderOutbox> failed = outboxMapper.selectFailed(batchSize);
        int alerted = 0;
        for (OrderOutbox o : failed) {
            if (alarm.raise(o.getTenantId(), ReconAlarm.OUTBOX_FAILED, "order_outbox:" + o.getId(),
                    "topic=" + o.getTopic() + " tag=" + o.getTag() + " bizKey=" + o.getBizKey()
                            + " retry=" + o.getRetryCount() + " lastError=" + o.getLastError())) {
                alerted++;
            }
        }
        return alerted;
    }
}
