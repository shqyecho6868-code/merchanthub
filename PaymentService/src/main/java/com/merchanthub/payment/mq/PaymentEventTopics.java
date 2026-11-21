package com.merchanthub.payment.mq;

/** 支付领域事件。order 订阅 PAY_SUCCESS 后自己做订单状态 CAS（payment 不直接改订单） */
public final class PaymentEventTopics {

    private PaymentEventTopics() {
    }

    public static final String TOPIC = "PAYMENT_EVENT";
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";
    /** 与 order 的 ORDER_EVENT 约定一致：自定义事件 ID 属性，消费端按它幂等 */
    public static final String PROP_EVENT_ID = "EVENT_ID";
    /** 这次回调的尝试 ID（每次回调随机生成）：回查时和支付单上记录的赢家 ID 比对 */
    public static final String PROP_ATTEMPT = "PAY_ATTEMPT";
}
