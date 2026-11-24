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
 * payment 侧对账（E3）：以支付单为准，核对订单那边对不对得上。两件事：
 * <ol>
 *   <li><b>支付成功 ↔ 订单</b>：成功超过宽限期的支付单，问 order "这张支付单的成功事件你处理过没有"。
 *     <ul>
 *       <li>处理过 → 一致（订单已支付，或 order 已把它记成 pay_anomaly，由 order 侧对账标记退款）</li>
 *       <li>没处理过 → 事件丢了（事务消息回查超限被丢弃 / 进了死信）→ <b>补发同一个事件</b>（eventId = payNo 不变）。
 *           订单还是待支付 → order 正常消费把它改成已支付；订单已关闭 → order 记 PAID_AFTER_CLOSED。
 *           不直接改订单：订单只有 order 一个写入方，修复也走它自己的幂等消费 + 状态机 CAS</li>
 *       <li>补发多次仍没处理 / 订单根本不存在 → 停止自动处理，记异常 + 告警</li>
 *     </ul>
 *   </li>
 *   <li><b>关单时没关闭的支付单</b>：创建很久仍待支付，而订单已关闭（或已被别的支付单付掉）→ 关闭支付单。
 *       不关的话，买家之后在旧支付页付款，回调照样成功 → 又一笔 PAID_AFTER_CLOSED</li>
 * </ol>
 * 只看超过宽限期的数据：刚成功的支付单，事件大概率还在路上，现在问 order 只会得到"没处理"、白白补发一次。
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

    /** @return [一致, 补发, 告警] */
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
            // 查不到 ≠ 没处理：order 不可用 / 熔断中，这一轮整体跳过，绝不在不确定时补发或告警
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
                // 钱收了，订单却不存在：补发也没用（order 会一直重试到死信），直接转人工
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

    /** 补发"支付成功"事件：普通消息即可（支付单早已成功，不再需要事务消息保证"本地事务和消息同生共死"） */
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

    /** @return 本轮关闭的支付单数 */
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
                continue;   // 订单还能付：买家可能正在付这一张，不能关
            }
            // 订单已关闭 / 已被别的支付单付掉 / 不存在：这张支付单永远不该再成功
            if (paymentMapper.closeIfPending(p.getPayNo()) == 1) {
                closed++;
                log.warn("【支付对账】订单状态 {}，关闭遗留的待支付支付单 payNo={} orderNo={} tenantId={}（真实渠道还需调关单接口）",
                        s, p.getPayNo(), p.getOrderNo(), p.getTenantId());
            }
        }
        return closed;
    }
}
