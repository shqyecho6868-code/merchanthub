package com.merchanthub.order.service;

/**
 * 下单最后一步：落订单，并保证"订单已创建"事件和订单同生共死。
 * <p>
 * 两种实现由 {@code merchanthub.order.event-mode} 二选一（B2 笔记第 3 节 Q1）：
 * <ul>
 *   <li>TX_MESSAGE：RocketMQ 事务消息（半消息 + 本地事务 + 回查）</li>
 *   <li>OUTBOX：本地消息表（订单和待发消息在同一个本地事务里写入，定时扫描补发）</li>
 * </ul>
 * 抛异常 = 订单没建成，事件也一定不会被消费者看到。
 */
public interface OrderPlacer {

    void place(NewOrder order);
}
