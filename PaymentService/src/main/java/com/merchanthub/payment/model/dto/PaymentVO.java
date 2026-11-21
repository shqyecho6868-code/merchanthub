package com.merchanthub.payment.model.dto;

public record PaymentVO(long payNo, long orderNo, long amount, int status) {
}
