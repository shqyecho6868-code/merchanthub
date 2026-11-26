package com.merchanthub.payment.mq;

/** Payment domain events. order subscribes to PAY_SUCCESS and does its own order-status CAS (payment never modifies orders directly) */
public final class PaymentEventTopics {

    private PaymentEventTopics() {
    }

    public static final String TOPIC = "PAYMENT_EVENT";
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";
    /** Matches order's ORDER_EVENT convention: a custom event-ID property that consumers dedupe on */
    public static final String PROP_EVENT_ID = "EVENT_ID";
    /** Attempt ID of this callback (randomly generated per callback): compared on check-back with the winner ID recorded on the payment */
    public static final String PROP_ATTEMPT = "PAY_ATTEMPT";
}
