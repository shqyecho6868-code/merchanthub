package com.merchanthub.order.mq.payment;

/**
 * 支付成功事件到了，订单却还查不到（回调早于订单落库 / 提交）。
 * 抛它是为了让整个消费事务回滚 —— 包括刚插入的 consumed_event 去重记录 —— 再由 RocketMQ 稍后重投。
 * 只 return RECONSUME_LATER 而让事务提交的话，重投时会被去重记录挡住当成"处理过了"，事件照样丢。
 */
public class OrderNotVisibleException extends RuntimeException {

    public OrderNotVisibleException(long orderNo) {
        super("订单尚不可见 orderNo=" + orderNo);
    }
}
