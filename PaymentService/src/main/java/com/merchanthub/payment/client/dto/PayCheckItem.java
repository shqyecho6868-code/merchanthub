package com.merchanthub.payment.client.dto;

/** Asked of order during reconciliation: has this payment's "payment succeeded" event been processed (fields match order's record of the same name) */
public record PayCheckItem(long payNo, long orderNo) {
}
