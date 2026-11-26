package com.merchanthub.payment.mq;

import java.util.function.BooleanSupplier;

/**
 * The local transaction handed to the transactional message: returning true means "this callback won the pending→succeeded CAS".
 * The result stays on the object; after the send method returns, the caller uses it to decide what to log and what to reply.
 */
public final class CasTx {

    private final BooleanSupplier body;
    private Boolean won;
    private RuntimeException failure;

    public CasTx(BooleanSupplier body) {
        this.body = body;
    }

    /** Called by PaymentTxListener on the sending thread, exactly once */
    boolean run() {
        try {
            won = body.getAsBoolean();
        } catch (RuntimeException e) {
            won = false;
            failure = e;
        }
        return won;
    }

    public boolean won() {
        return Boolean.TRUE.equals(won);
    }

    public RuntimeException failure() {
        return failure;
    }
}
