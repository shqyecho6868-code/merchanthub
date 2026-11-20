package com.merchanthub.order.mq.tx;

/**
 * 交给 {@code sendMessageInTransaction(msg, arg)} 的 arg：本地事务本身 + 执行结果。
 * <p>
 * 为什么要把结果带回来：RocketMQ 客户端会吞掉 executeLocalTransaction 里抛出的异常（只记日志、按 UNKNOW 处理），
 * 调用方拿不到"库存不足""数据库挂了"这些原始异常。所以监听器自己 catch 住异常放这里，由 placer 再抛给上层。
 */
final class LocalTx {

    private final Runnable work;
    private boolean executed;
    private RuntimeException failure;

    LocalTx(Runnable work) {
        this.work = work;
    }

    /** @return true 表示本地事务已提交 */
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

    /** false：半消息没发成功，本地事务根本没执行 */
    boolean executed() {
        return executed;
    }

    RuntimeException failure() {
        return failure;
    }
}
