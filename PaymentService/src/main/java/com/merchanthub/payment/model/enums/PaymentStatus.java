package com.merchanthub.payment.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Payment status (data-model 3.6). Only pending → succeeded / failed / closed is allowed; all three are terminal */
@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING(0),
    SUCCESS(1),
    FAILED(2),
    CLOSED(3);

    private final int code;
}
