package com.merchanthub.realtime.push;

/** realtime 用到的 Topic / 消费组 */
public final class PushTopics {

    private PushTopics() {
    }

    /** 订单领域事件（order 发出），与 order 的 OrderEventTopics 一致 */
    public static final String ORDER_TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_CREATED = "ORDER_CREATED";
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";
    /** 集群消费：所有节点同组，一条订单事件只被一台节点消费 */
    public static final String ORDER_CONSUMER_GROUP = "realtime-order-event";

    /**
     * 节点间转发：Tag = 目标节点 ID。每个节点用<b>自己专属的消费组</b>（realtime-push-{节点ID}）只订阅自己的 Tag。
     * 不能所有节点同组、各订阅不同 Tag：RocketMQ 要求同组订阅关系一致，否则队列分给了 A、消息却是 B 的 Tag，会被直接过滤丢掉
     */
    public static final String PUSH_TOPIC = "REALTIME_PUSH";

    public static String nodeConsumerGroup(String nodeId) {
        return "realtime-push-" + nodeId;
    }
}
