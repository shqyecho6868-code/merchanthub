package com.merchanthub.realtime.push;

/** Topics / consumer groups used by realtime */
public final class PushTopics {

    private PushTopics() {
    }

    /** Order domain events (sent by order), must match order's OrderEventTopics */
    public static final String ORDER_TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_CREATED = "ORDER_CREATED";
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";
    /** Clustering mode: all nodes in one group, each order event is consumed by only one node */
    public static final String ORDER_CONSUMER_GROUP = "realtime-order-event";

    /**
     * Inter-node forwarding: Tag = target node ID. Each node uses <b>its own dedicated consumer group</b> (realtime-push-{nodeId}) subscribing only to its own Tag.
     * Can't put all nodes in one group with different Tags each: RocketMQ requires consistent subscriptions within a group; otherwise a queue assigned to A may carry B's Tag and get filtered out and dropped
     */
    public static final String PUSH_TOPIC = "REALTIME_PUSH";

    public static String nodeConsumerGroup(String nodeId) {
        return "realtime-push-" + nodeId;
    }
}
