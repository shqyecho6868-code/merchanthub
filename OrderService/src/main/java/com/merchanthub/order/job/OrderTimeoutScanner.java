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
 * Timeout-order scan (E3, left over from B3-下): the main path is the ORDER_TIMEOUT scheduled message; this is the safety net for when it never arrives --
 * outbox delivery marked as failed (2), message retries exhausted into the DLQ, or the broker lost the scheduled message.
 * <p>
 * Only scans orders "past the deadline + grace period": within the grace period the scheduled message is most likely in flight, so racing to close is pointless (closing is idempotent, just wasteful).
 * The close uses the same CAS as the message path, so only one side wins against payment success.
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

    /** @return number of orders closed in this round */
    public int scan() {
        List<Orders> expired = TenantContext.callAsSystem(() ->
                ordersMapper.selectExpiredPending(LocalDateTime.now(clock).minus(grace), batchSize));
        int closed = 0;
        for (Orders o : expired) {
            try {
                // Run as the order's merchant: the status log and outbox both carry this merchant
                if (TenantContext.callAs(TenantInfo.systemOf(o.getTenantId()),
                        () -> timeoutService.closeExpired(o.getOrderNo(), o.getTenantId()))) {
                    closed++;
                    log.warn("[Timeout-order scan] Scheduled close message didn't take effect, closed as fallback orderNo={} tenantId={}", o.getOrderNo(), o.getTenantId());
                }
            } catch (RuntimeException e) {
                log.warn("Timeout-order scan: close failed, retrying next round orderNo={}", o.getOrderNo(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Timeout-order scan done candidates={} closed={}", expired.size(), closed);
        }
        return closed;
    }
}
