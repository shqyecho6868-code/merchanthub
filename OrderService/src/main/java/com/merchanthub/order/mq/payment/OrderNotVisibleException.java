package com.merchanthub.order.mq.payment;

/**
 * A payment-success event arrived but the order can't be found yet (callback before the order was persisted / committed).
 * It's thrown to roll back the whole consumer transaction -- including the just-inserted consumed_event dedup record -- so RocketMQ redelivers later.
 * Just returning RECONSUME_LATER while committing the transaction would let the dedup record block the redelivery as "already processed", losing the event anyway.
 */
public class OrderNotVisibleException extends RuntimeException {

    public OrderNotVisibleException(long orderNo) {
        super("Order not visible yet orderNo=" + orderNo);
    }
}
