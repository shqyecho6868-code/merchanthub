package com.merchanthub.payment.job;

import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.recon.ReconAlarm;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.payment.client.OrderClient;
import com.merchanthub.payment.client.OrderRemote;
import com.merchanthub.payment.client.dto.PayCheckItem;
import com.merchanthub.payment.client.dto.PayCheckResult;
import com.merchanthub.payment.mapper.PaymentMapper;
import com.merchanthub.payment.model.entity.Payment;
import com.merchanthub.payment.mq.PaySuccessEvent;
import com.merchanthub.payment.mq.PaymentEventCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * payment-side reconciliation (E3): treat the payment as the source of truth and check whether the order side matches. Two things:
 * <ol>
 *   <li><b>Payment succeeded ↔ order</b>: for payments that succeeded longer ago than the grace period, ask order "have you processed this payment's success event".
 *     <ul>
 *       <li>Processed → consistent (order is paid, or order has recorded it as pay_anomaly and order-side reconciliation flags it for refund)</li>
 *       <li>Not processed → the event was lost (transactional message dropped after exceeding check-back limits / went to DLQ) → <b>re-send the same event</b> (eventId = payNo unchanged).
 *           Order still pending payment → order consumes it normally and marks it paid; order closed → order records PAID_AFTER_CLOSED.
 *           We don't modify the order directly: order is its only writer, so the fix also goes through its own idempotent consumption + state-machine CAS</li>
 *       <li>Still unprocessed after several re-sends / order doesn't exist at all → stop auto-handling, record an anomaly + alert</li>
 *     </ul>
 *   </li>
 *   <li><b>Payments left open when the order was closed</b>: created long ago and still pending while the order is closed (or paid by another payment) → close the payment.
 *       Otherwise, if the buyer later pays on the old payment page, the callback still succeeds → yet another PAID_AFTER_CLOSED</li>
 * </ol>
 * Only data older than the grace period is examined: for a just-succeeded payment the event is most likely still in flight; asking order now would only get "not processed" and trigger a pointless re-send.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciler {

    static final int ORDER_PENDING = 10;

    private final PaymentMapper paymentMapper;
    private final OrderClient orderClient;
    private final TransactionMQProducer producer;
    private final PaymentEventCodec codec;
    private final ReconAlarm alarm;
    private final Clock clock;

    @Value("${merchanthub.payment.recon.grace:2m}")
    private Duration grace;
    @Value("${merchanthub.payment.recon.pending-close-after:30m}")
    private Duration pendingCloseAfter;
    @Value("${merchanthub.payment.recon.max-resend:3}")
    private int maxResend;
    @Value("${merchanthub.payment.recon.batch-size:200}")
    private int batchSize;

    public record Result(int consistent, int resent, int alerted, int closedPayments) {
    }

    public Result reconcile() {
        return TenantContext.callAsSystem(() -> {
            int[] success = reconcileSuccess();
            return new Result(success[0], success[1], success[2], closeStalePending());
        });
    }

    /** @return [consistent, re-sent, alerted] */
    private int[] reconcileSuccess() {
        int consistent = 0, resent = 0, alerted = 0;
        List<Payment> payments = paymentMapper.selectSuccessUnreconciled(LocalDateTime.now(clock).minus(grace), batchSize);
        if (payments.isEmpty()) {
            return new int[]{0, 0, 0};
        }
        List<PayCheckResult> results;
        try {
            results = OrderRemote.call(OrderClient.RES_PAY_CHECK, () -> orderClient.payCheck(
                    payments.stream().map(p -> new PayCheckItem(p.getPayNo(), p.getOrderNo())).toList()));
        } catch (BusinessException e) {
            // "Can't look it up" ≠ "not processed": order unavailable / circuit open, so skip the whole round — never re-send or alert when uncertain
            log.warn("支付对账：查询 order 失败，本轮跳过 候选={}", payments.size(), e);
            return new int[]{0, 0, 0};
        }
        Map<Long, Payment> byPayNo = payments.stream().collect(Collectors.toMap(Payment::getPayNo, Function.identity()));
        for (PayCheckResult r : results) {
            Payment p = byPayNo.get(r.payNo());
            if (p == null) {
                continue;
            }
            if (r.processed()) {
                consistent += paymentMapper.markReconciled(p.getPayNo());
            } else if (r.orderStatus() == null) {
                // Money collected but the order doesn't exist: re-sending won't help (order would retry until DLQ), escalate to manual handling directly
                alerted += exhaust(p, ReconAlarm.PAY_MISMATCH, "支付成功但订单不存在");
            } else if (p.getReconRetry() >= maxResend) {
                alerted += exhaust(p, ReconAlarm.PAY_RESEND_EXHAUSTED,
                        "补发 " + p.getReconRetry() + " 次后 order 仍未处理，订单状态=" + r.orderStatus());
            } else if (resend(p, r.orderStatus())) {
                resent++;
            }
        }
        log.info("支付对账（成功单）完成 候选={} 一致={} 补发={} 告警={}", payments.size(), consistent, resent, alerted);
        return new int[]{consistent, resent, alerted};
    }

    /** Re-send the "payment succeeded" event: a normal message is enough (the payment succeeded long ago; no need for a transactional message to tie "local transaction and message" together) */
    private boolean resend(Payment p, int orderStatus) {
        PaySuccessEvent event = new PaySuccessEvent(p.getPayNo(), p.getPayNo(), p.getOrderNo(), p.getTenantId(),
                p.getBuyerId(), p.getAmount(), p.getChannelTradeNo(), p.getPaidAt());
        try {
            SendResult sr = producer.send(codec.toMessage(event, "RECON-" + (p.getReconRetry() + 1)));
            if (sr.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("支付对账：补发未确认 payNo={} status={}", p.getPayNo(), sr.getSendStatus());
                return false;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("支付对账：补发失败，下一轮再试 payNo={}", p.getPayNo(), e);
            return false;
        }
        paymentMapper.incrReconRetry(p.getPayNo(), p.getReconRetry());
        log.warn("【支付对账】支付成功事件 order 未处理（订单状态 {}{}），已补发第 {} 次 payNo={} orderNo={} tenantId={}",
                orderStatus, orderStatus == ORDER_PENDING ? "，补发后订单将变为已支付" : "，补发后 order 将记支付异常",
                p.getReconRetry() + 1, p.getPayNo(), p.getOrderNo(), p.getTenantId());
        return true;
    }

    private int exhaust(Payment p, String type, String detail) {
        alarm.raise(p.getTenantId(), type, "payNo:" + p.getPayNo(),
                detail + " orderNo=" + p.getOrderNo() + " amount=" + p.getAmount());
        return paymentMapper.markReconExhausted(p.getPayNo());
    }

    /** @return number of payments closed this round */
    private int closeStalePending() {
        List<Payment> stale = paymentMapper.selectStalePending(LocalDateTime.now(clock).minus(pendingCloseAfter), batchSize);
        if (stale.isEmpty()) {
            return 0;
        }
        Map<Long, Integer> status;
        try {
            status = OrderRemote.call(OrderClient.RES_STATUSES,
                    () -> orderClient.statuses(stale.stream().map(Payment::getOrderNo).distinct().toList()));
        } catch (BusinessException e) {
            log.warn("支付对账：查询订单状态失败，本轮不关支付单 候选={}", stale.size(), e);
            return 0;
        }
        int closed = 0;
        for (Payment p : stale) {
            Integer s = status.get(p.getOrderNo());
            if (s != null && s == ORDER_PENDING) {
                continue;   // The order can still be paid: the buyer may be paying with this one right now, can't close it
            }
            // Order closed / paid by another payment / missing: this payment must never succeed again
            if (paymentMapper.closeIfPending(p.getPayNo()) == 1) {
                closed++;
                log.warn("【支付对账】订单状态 {}，关闭遗留的待支付支付单 payNo={} orderNo={} tenantId={}（真实渠道还需调关单接口）",
                        s, p.getPayNo(), p.getOrderNo(), p.getTenantId());
            }
        }
        return closed;
    }
}
