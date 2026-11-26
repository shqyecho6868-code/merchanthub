package com.merchanthub.order.service;

/**
 * Last step of order placement: persist the order and guarantee the "order created" event lives or dies with it.
 * <p>
 * Two implementations, selected by {@code merchanthub.order.event-mode} (B2 notes section 3 Q1):
 * <ul>
 *   <li>TX_MESSAGE: RocketMQ transactional message (half message + local transaction + check-back)</li>
 *   <li>OUTBOX: local message table (order and pending message written in the same local transaction, re-sent by a periodic scan)</li>
 * </ul>
 * Throws = the order wasn't created, and consumers will never see the event.
 */
public interface OrderPlacer {

    void place(NewOrder order);
}
