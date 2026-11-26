package com.merchanthub.order.service;

import java.time.LocalDateTime;

/**
 * A new order ready to persist (validation, pricing, and reservation all done).
 * The order number is generated before reservation and is the business key shared by the reservation and the "order created" event.
 */
public record NewOrder(long orderNo, long tenantId, long buyerId, OrderPricing.Result pricing,
                       LocalDateTime createdAt, LocalDateTime expireAt) {
}
