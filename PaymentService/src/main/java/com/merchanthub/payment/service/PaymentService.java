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

    /** 回调处理结果。除 PAID 外，其余对渠道都回"成功"：告诉渠道"这条通知我收到了，别再重试" */
    public enum CallbackOutcome { PAID, DUPLICATE, IGNORED }

    private final PaymentMapper paymentMapper;
    private final OrderClient orderClient;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionMQProducer producer;
    private final PaymentEventCodec codec;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final ReconAlarm reconAlarm;

    /** 建支付单：每次"去支付"都建一条新的（订单与支付单一对多），金额以订单为准，不信任客户端 */
    public PaymentVO create(long buyerId, long orderNo) {
        OrderBrief order = queryOrder(orderNo);
        // 不是自己的订单也报"不存在"：不告诉对方这个订单号是有效的（防越权探测）
        if (order == null || !Objects.equals(order.getBuyerId(), buyerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "订单不存在");
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
        log.info("支付单已创建 payNo={} orderNo={} tenantId={} amount={}", p.getPayNo(), orderNo, p.getTenantId(), p.getAmount());
        return new PaymentVO(p.getPayNo(), orderNo, p.getAmount(), p.getStatus());
    }

    /**
     * 处理渠道的"支付成功"通知。同一笔支付可能通知多次（渠道没收到我们的响应就会重试），挡重复分两层：
     * <ol>
     *   <li>快速路径：读到支付单已成功 → 直接回成功，连 MQ 都不碰（先后到达的重复通知在这里挡住）</li>
     *   <li>CAS：{@code WHERE status = 0} 的条件更新（同时到达的重复通知都读到"待支付"，在这里只有一个能赢）</li>
     * </ol>
     * 只有赢了 CAS 的那一次，事务消息才 COMMIT，产生唯一一条"支付成功"事件。
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
        // 金额必须和支付单一致：防止"付 1 分钱回调成功、订单却按全额标成已支付"
        if (!Objects.equals(p.getAmount(), req.amount())) {
            log.error("回调金额不符，拒绝 payNo={} orderNo={} 支付单金额={} 回调金额={}", p.getPayNo(), p.getOrderNo(), p.getAmount(), req.amount());
            throw new BusinessException(ErrorCode.PAY_AMOUNT_MISMATCH);
        }
        if (p.getStatus() == PaymentStatus.SUCCESS.getCode()) {
            if (req.channelTradeNo().equals(p.getChannelTradeNo())) {
                log.info("【幂等·第一层 快速路径】重复回调：支付单已是成功，直接返回成功，不再发事件 payNo={} orderNo={} channelTradeNo={}",
                        p.getPayNo(), p.getOrderNo(), req.channelTradeNo());
            } else {
                // 同一张支付单出现两个不同的渠道流水号：渠道侧异常或伪造通知，交给对账人工看
                log.error("支付单已成功，但回调带了不同的渠道流水号，需对账 payNo={} 已记录={} 本次={}",
                        p.getPayNo(), p.getChannelTradeNo(), req.channelTradeNo());
            }
            return CallbackOutcome.DUPLICATE;
        }
        if (p.getStatus() != PaymentStatus.PENDING.getCode()) {
            // 支付单已关闭 / 失败后钱又到了（例：对账刚关掉遗留支付单，买家还在旧页面付了款）：钱收了货不发，待退款（退款不做）。
            // E3：记异常表 + 告警，转人工
            reconAlarm.raise(p.getTenantId(), ReconAlarm.PAID_AFTER_CLOSED, "payNo:" + p.getPayNo(),
                    "支付单状态 " + p.getStatus() + " 时收到支付成功回调，待退款 orderNo=" + p.getOrderNo()
                            + " amount=" + p.getAmount() + " channelTradeNo=" + req.channelTradeNo());
            return CallbackOutcome.IGNORED;
        }

        LocalDateTime paidAt = LocalDateTime.now(clock);
        PaySuccessEvent event = new PaySuccessEvent(p.getPayNo(), p.getPayNo(), p.getOrderNo(), p.getTenantId(),
                p.getBuyerId(), p.getAmount(), req.channelTradeNo(), paidAt);
        // 每次回调一个尝试 ID：CAS 赢了就记到支付单上，事务回查据此只提交赢家的那条半消息
        String attempt = UUID.randomUUID().toString();
        CasTx tx = new CasTx(() -> Boolean.TRUE.equals(txTemplate.execute(s ->
                paymentMapper.markSuccess(p.getPayNo(), req.channelTradeNo(), paidAt, attempt) == 1)));
        try {
            // 半消息发不出去（MQ 不可用）会在这里抛异常：支付单保持待支付，回调返回失败，渠道稍后重试 —— 不会"钱收了事件丢了"
            producer.sendMessageInTransaction(codec.toMessage(event, attempt), tx);
        } catch (MQClientException e) {
            log.warn("支付成功事件发送失败，支付单保持待支付，等渠道重试 payNo={}", p.getPayNo(), e);
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        }
        if (tx.failure() instanceof DuplicateKeyException) {
            log.error("渠道流水号已被其他支付单使用，拒绝 payNo={} channelTradeNo={}", p.getPayNo(), req.channelTradeNo());
            throw new BusinessException(ErrorCode.CHANNEL_TRADE_NO_CONFLICT);
        }
        if (tx.failure() != null) {
            throw tx.failure();
        }
        if (!tx.won()) {
            log.info("【幂等·第二层 CAS】并发重复回调：UPDATE ... WHERE status = 0 影响 0 行，半消息已回滚 payNo={} orderNo={} channelTradeNo={}",
                    p.getPayNo(), p.getOrderNo(), req.channelTradeNo());
            return CallbackOutcome.DUPLICATE;
        }
        log.info("【支付成功】CAS 待支付→成功，支付成功事件已提交 payNo={} orderNo={} channelTradeNo={} amount={}",
                p.getPayNo(), p.getOrderNo(), req.channelTradeNo(), p.getAmount());
        return CallbackOutcome.PAID;
    }

    /** 超时 / 连不上 / 熔断中 → DOWNSTREAM_UNAVAILABLE（降级）；订单不存在等业务失败原样透传（E3 起经 Sentinel 熔断） */
    private OrderBrief queryOrder(long orderNo) {
        return OrderRemote.call(OrderClient.RES_GET_ORDER, () -> orderClient.getOrder(orderNo));
    }
}
