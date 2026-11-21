package com.merchanthub.payment.mq;

import java.util.function.BooleanSupplier;

/**
 * 交给事务消息执行的本地事务：返回 true 表示"这次回调赢得了 待支付→成功 的 CAS"。
 * 执行结果留在对象里，发送方法返回后调用方据此决定打什么日志、回什么。
 */
public final class CasTx {

    private final BooleanSupplier body;
    private Boolean won;
    private RuntimeException failure;

    public CasTx(BooleanSupplier body) {
        this.body = body;
    }

    /** 由 PaymentTxListener 在发送线程里调用，只调一次 */
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
