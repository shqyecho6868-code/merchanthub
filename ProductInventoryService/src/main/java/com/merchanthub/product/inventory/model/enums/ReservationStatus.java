package com.merchanthub.product.inventory.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Reservation record status. CONFIRMED and RELEASED are both terminal and mutually exclusive:
 * B2/B3 use {@code UPDATE ... WHERE status = RESERVED} as a CAS, so when payment confirmation and timeout release arrive together only one succeeds.
 */
@Getter
@AllArgsConstructor
public enum ReservationStatus {
    RESERVED(10),
    CONFIRMED(20),
    RELEASED(30);

    private final int code;
}
