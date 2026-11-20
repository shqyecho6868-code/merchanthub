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

    /** 消息上的自定义属性：我们自己的事件 ID，消费端按它幂等（不用 RocketMQ 的 msgId，见 B2 笔记 Q4） */
    public static final String PROP_EVENT_ID = "EVENT_ID";
}
