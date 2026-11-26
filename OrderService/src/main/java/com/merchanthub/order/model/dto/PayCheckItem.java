package com.merchanthub.order.model.dto;

/** payment asks order during reconciliation: has the order processed this payment's "payment succeeded" event? */
public record PayCheckItem(long payNo, long orderNo) {
}
