package com.merchanthub.order.job;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.mq.timeout.PayTimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单扫描（E3，B3-下遗留）：主路径是 ORDER_TIMEOUT 定时消息，这里兜底"定时消息没来"的情况 ——
 * outbox 投递失败标成 2、消息重试耗尽进了死信、Broker 丢了定时消息。
 * <p>
 * 只扫"过了截止时间 + 宽限期"的订单：宽限期内定时消息大概率正在路上，抢着关单没有意义（关是幂等的，只是浪费）。
 * 关单和消息路径走同一个 CAS，和支付成功竞争时只会有一方赢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutScanner {

    private final OrdersMapper ordersMapper;
    private final PayTimeoutService timeoutService;
    private final Clock clock;

    @Value("${merchanthub.order.jobs.timeout-grace:5m}")
    private Duration grace;
    @Value("${merchanthub.order.jobs.batch-size:200}")
    private int batchSize;

    /** @return 本轮关掉的订单数 */
    public int scan() {
        List<Orders> expired = TenantContext.callAsSystem(() ->
                ordersMapper.selectExpiredPending(LocalDateTime.now(clock).minus(grace), batchSize));
        int closed = 0;
        for (Orders o : expired) {
            try {
                // 按订单所属商家执行：状态流水、outbox 都带这个商家
                if (TenantContext.callAs(TenantInfo.systemOf(o.getTenantId()),
                        () -> timeoutService.closeExpired(o.getOrderNo(), o.getTenantId()))) {
                    closed++;
                    log.warn("【超时订单扫描】定时关单消息没生效，已兜底关单 orderNo={} tenantId={}", o.getOrderNo(), o.getTenantId());
                }
            } catch (RuntimeException e) {
                log.warn("超时订单扫描：关单失败，下一轮再试 orderNo={}", o.getOrderNo(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("超时订单扫描完成 候选={} 关单={}", expired.size(), closed);
        }
        return closed;
    }
}
