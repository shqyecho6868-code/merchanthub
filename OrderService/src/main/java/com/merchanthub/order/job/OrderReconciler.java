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
 * order 侧对账（E3）：处理"自动修不了、只能标记 + 告警"的两类问题。
 * <ol>
 *   <li><b>pay_anomaly</b>：钱收了、订单没成（关单后支付 / 重复支付 / 金额商家不符）。
 *       <b>不改订单</b>：已关闭的订单库存早已释放、可能已被别人买走，"复活"成已支付就会超卖或发不出货；
 *       正确做法是退款（范围外）。这里标记 handle_status = 2（待退款）+ 记 recon_exception + 告警</li>
 *   <li><b>outbox 投递失败（status = 2）</b>：重试满上限仍发不出去，告警等人工排查后改回 0 重新投递</li>
 * </ol>
 * "支付成功但订单还是待支付"不在这里修：那是 payment 侧对账发现后补发支付成功事件，由 order 正常消费修好。
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
                    "待退款 orderNo=" + a.getOrderNo() + " amount=" + a.getAmount() + " 记录时订单状态=" + a.getOrderStatus()
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
