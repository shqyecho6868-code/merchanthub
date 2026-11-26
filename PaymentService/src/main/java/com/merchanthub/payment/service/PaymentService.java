package com.merchanthub.payment.service;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.common.recon.ReconAlarm;
import com.merchanthub.payment.client.OrderClient;
import com.merchanthub.payment.client.dto.OrderBrief;
import com.merchanthub.payment.mapper.PaymentMapper;
import com.merchanthub.payment.model.dto.MockCallbackRequest;
import com.merchanthub.payment.model.dto.PaymentVO;
import com.merchanthub.payment.model.entity.Payment;
import com.merchanthub.payment.model.enums.PaymentStatus;
import com.merchanthub.payment.mq.CasTx;
import com.merchanthub.payment.mq.PaySuccessEvent;
import com.merchanthub.payment.mq.PaymentEventCodec;
import com.merchanthub.payment.client.OrderRemote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Callback processing result. Everything except PAID still replies "success" to the channel: telling it "got this notification, stop retrying" */
    public enum CallbackOutcome { PAID, DUPLICATE, IGNORED }

    private final PaymentMapper paymentMapper;
    private final OrderClient orderClient;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionMQProducer producer;
    private final PaymentEventCodec codec;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final ReconAlarm reconAlarm;

    /** Create a payment: every "go pay" creates a new one (order-to-payment is one-to-many); the amount comes from the order, never trust the client */
    public PaymentVO create(long buyerId, long orderNo) {
        OrderBrief order = queryOrder(orderNo);
        // Report "not found" even for someone else's order: don't reveal that the order number is valid (prevents enumeration of other users' orders)
        if (order == null || !Objects.equals(order.getBuyerId(), buyerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Order not found");
        }
        if (order.getStatus() != OrderBrief.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        Payment p = new Payment();
        p.setTenantId(order.getTenantId());
        p.setPayNo(idGenerator.nextId());
        p.setOrderNo(orderNo);
        p.setBuyerId(buyerId);
        p.setAmount(order.getPayAmount());
        p.setChannel("MOCK");
        p.setStatus(PaymentStatus.PENDING.getCode());
        paymentMapper.insert(p);
        log.info("Payment created payNo={} orderNo={} tenantId={} amount={}", p.getPayNo(), orderNo, p.getTenantId(), p.getAmount());
        return new PaymentVO(p.getPayNo(), orderNo, p.getAmount(), p.getStatus());
    }

    /**
     * Handle the channel's "payment succeeded" notification. The same payment may be notified multiple times (the channel retries if it doesn't get our response); duplicates are stopped in two layers:
     * <ol>
     *   <li>Fast path: payment already succeeded → reply success right away without touching MQ (stops duplicates that arrive one after another)</li>
     *   <li>CAS: a conditional update {@code WHERE status = 0} (duplicates arriving at the same time all read "pending"; only one can win here)</li>
     * </ol>
     * Only the callback that wins the CAS gets its transactional message COMMITted, producing the single "payment succeeded" event.
     */
    public CallbackOutcome handleMockCallback(MockCallbackRequest req) {
        Payment p = paymentMapper.selectByPayNo(req.payNo());
        if (p == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        MDC.put("tenantId", String.valueOf(p.getTenantId()));
        try {
            return doHandle(p, req);
        } finally {
            MDC.remove("tenantId");
        }
    }

    private CallbackOutcome doHandle(Payment p, MockCallbackRequest req) {
        // The amount must match the payment: prevents "pay 1 cent, callback succeeds, and the order gets marked paid for the full amount"
        if (!Objects.equals(p.getAmount(), req.amount())) {
            log.error("Callback amount mismatch, rejected payNo={} orderNo={} paymentAmount={} callbackAmount={}", p.getPayNo(), p.getOrderNo(), p.getAmount(), req.amount());
            throw new BusinessException(ErrorCode.PAY_AMOUNT_MISMATCH);
        }
        if (p.getStatus() == PaymentStatus.SUCCESS.getCode()) {
            if (req.channelTradeNo().equals(p.getChannelTradeNo())) {
                log.info("[Idempotency L1 fast path] duplicate callback: payment already succeeded, returning success without sending another event payNo={} orderNo={} channelTradeNo={}",
                        p.getPayNo(), p.getOrderNo(), req.channelTradeNo());
            } else {
                // Two different channel transaction numbers on the same payment: a channel-side anomaly or a forged notification — leave it to reconciliation / manual review
                log.error("Payment already succeeded but the callback carries a different channel transaction number, needs reconciliation payNo={} recorded={} current={}",
                        p.getPayNo(), p.getChannelTradeNo(), req.channelTradeNo());
            }
            return CallbackOutcome.DUPLICATE;
        }
        if (p.getStatus() != PaymentStatus.PENDING.getCode()) {
            // Money arrives after the payment is closed / failed (e.g. recon just closed a stale payment while the buyer paid on the old page): money collected but no goods shipped, pending refund (refunds out of scope).
            // Record in the anomaly table + alert, escalate to manual handling
            reconAlarm.raise(p.getTenantId(), ReconAlarm.PAID_AFTER_CLOSED, "payNo:" + p.getPayNo(),
                    "Payment-success callback received while payment status is " + p.getStatus() + ", pending refund orderNo=" + p.getOrderNo()
                            + " amount=" + p.getAmount() + " channelTradeNo=" + req.channelTradeNo());
            return CallbackOutcome.IGNORED;
        }

        LocalDateTime paidAt = LocalDateTime.now(clock);
        PaySuccessEvent event = new PaySuccessEvent(p.getPayNo(), p.getPayNo(), p.getOrderNo(), p.getTenantId(),
                p.getBuyerId(), p.getAmount(), req.channelTradeNo(), paidAt);
        // One attempt ID per callback: if the CAS wins it's recorded on the payment, and the tx check-back uses it to commit only the winner's half message
        String attempt = UUID.randomUUID().toString();
        CasTx tx = new CasTx(() -> Boolean.TRUE.equals(txTemplate.execute(s ->
                paymentMapper.markSuccess(p.getPayNo(), req.channelTradeNo(), paidAt, attempt) == 1)));
        try {
            // If the half message can't be sent (MQ unavailable) it throws here: the payment stays pending, the callback returns failure, and the channel retries later — never "money collected but event lost"
            producer.sendMessageInTransaction(codec.toMessage(event, attempt), tx);
        } catch (MQClientException e) {
            log.warn("Failed to send payment-success event, payment stays pending until the channel retries payNo={}", p.getPayNo(), e);
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        }
        if (tx.failure() instanceof DuplicateKeyException) {
            log.error("Channel transaction number already used by another payment, rejected payNo={} channelTradeNo={}", p.getPayNo(), req.channelTradeNo());
            throw new BusinessException(ErrorCode.CHANNEL_TRADE_NO_CONFLICT);
        }
        if (tx.failure() != null) {
            throw tx.failure();
        }
        if (!tx.won()) {
            log.info("[Idempotency L2 CAS] concurrent duplicate callback: UPDATE ... WHERE status = 0 affected 0 rows, half message rolled back payNo={} orderNo={} channelTradeNo={}",
                    p.getPayNo(), p.getOrderNo(), req.channelTradeNo());
            return CallbackOutcome.DUPLICATE;
        }
        log.info("[Payment succeeded] CAS pending→succeeded, payment-success event committed payNo={} orderNo={} channelTradeNo={} amount={}",
                p.getPayNo(), p.getOrderNo(), req.channelTradeNo(), p.getAmount());
        return CallbackOutcome.PAID;
    }

    /** Timeout / connection failure / circuit open → DOWNSTREAM_UNAVAILABLE (degraded); business failures such as order not found are passed through as-is (via the Sentinel circuit breaker) */
    private OrderBrief queryOrder(long orderNo) {
        return OrderRemote.call(OrderClient.RES_GET_ORDER, () -> orderClient.getOrder(orderNo));
    }
}
