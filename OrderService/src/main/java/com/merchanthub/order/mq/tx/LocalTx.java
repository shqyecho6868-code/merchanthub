package com.merchanthub.order.mq.tx;

/**
 * The arg passed to {@code sendMessageInTransaction(msg, arg)}: the local transaction itself + its result.
 * <p>
 * Why carry the result back: the RocketMQ client swallows exceptions thrown in executeLocalTransaction (just logs and treats as UNKNOW),
 * so the caller never sees the original exceptions like "insufficient stock" or "DB down". The listener catches them and stores them here, and the placer rethrows them upward.
 */
final class LocalTx {

    private final Runnable work;
    private boolean executed;
    private RuntimeException failure;

    LocalTx(Runnable work) {
        this.work = work;
    }

    /** @return true if the local transaction committed */
    boolean run() {
        executed = true;
        try {
            work.run();
            return true;
        } catch (RuntimeException e) {
            failure = e;
            return false;
        }
    }

    /** false: the half message wasn't sent, so the local transaction never ran */
    boolean executed() {
        return executed;
    }

    RuntimeException failure() {
        return failure;
    }
}
