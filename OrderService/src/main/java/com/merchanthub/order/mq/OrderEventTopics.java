package com.merchanthub.order.mq;

/**
 * 订单领域事件的 Topic / Tag。
 * 一个 Topic 装订单的所有事件，用 Tag 区分类型：下游按 Tag 订阅自己关心的事件，以后加"已支付""已关闭"不用新建 Topic。
 */
public final class OrderEventTopics {

    private OrderEventTopics() {
    }

    public static final String TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_CREATED = "ORDER_CREATED";
    /** 订单 CAS 10→20 成功后发出：库存据此把预占转为确认扣减 */
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    /** 订单 CAS 10→50 成功后发出：库存据此释放预占 */
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";

    /**
     * 超时关单的定时消息单独一个 Topic：RocketMQ 5 的 Topic 有消息类型（普通 / 定时 / 事务 / 顺序），
     * 定时消息和普通事件分开放，以后打开 Broker 的类型校验也不冲突，监控上也能单独看积压
     */
    public static final String TIMEOUT_TOPIC = "ORDER_TIMEOUT";
    public static final String TAG_PAY_TIMEOUT = "PAY_TIMEOUT";

    /** 消息上的自定义属性：我们自己的事件 ID，消费端按它幂等（不用 RocketMQ 的 msgId，见 B2 笔记 Q4） */
    public static final String PROP_EVENT_ID = "EVENT_ID";
}
