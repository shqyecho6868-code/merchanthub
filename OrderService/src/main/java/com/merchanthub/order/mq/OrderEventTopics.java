package com.merchanthub.order.mq;

/**
 * Topic / Tags for order domain events.
 * One Topic holds all order events, distinguished by Tag: downstreams subscribe by Tag to the events they care about, and adding "paid" or "closed" needs no new Topic.
 */
public final class OrderEventTopics {

    private OrderEventTopics() {
    }

    public static final String TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_CREATED = "ORDER_CREATED";
    /** Sent after order CAS 10->20 succeeds: inventory converts the reservation into a confirmed deduction */
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    /** Sent after order CAS 10->50 succeeds: inventory releases the reservation */
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";

    /**
     * Timeout-close scheduled messages get their own Topic: RocketMQ 5 Topics have message types (normal / scheduled / transactional / FIFO);
     * keeping scheduled messages apart from normal events avoids conflicts if the broker's type check is enabled later, and lets monitoring show their backlog separately
     */
    public static final String TIMEOUT_TOPIC = "ORDER_TIMEOUT";
    public static final String TAG_PAY_TIMEOUT = "PAY_TIMEOUT";

    /** Custom message property: our own event ID, which consumers use for idempotency (not RocketMQ's msgId, see B2 notes Q4) */
    public static final String PROP_EVENT_ID = "EVENT_ID";
}
