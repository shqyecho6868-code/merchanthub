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
 * Timeout-order scan: the main path is the ORDER_TIMEOUT scheduled message; this is the safety net for when it never arrives --
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

    /** One call keeps going for at most this long (XXL-Job executor timeout of orderTimeoutScanJob is 120s) */
    @Value("${merchanthub.order.jobs.time-budget:90s}")
    private Duration timeBudget = Duration.ofSeconds(90);

    /**
     * Catch-up: 200 orders per minute can't clear thousands of stuck orders, so keep taking batches while a batch comes back full
     * and the round actually closed something. "Closed nothing" stops the loop: the same rows would be selected again (e.g. close keeps failing).
     *
     * @return number of orders closed in this call
     */
    public int scan() {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        int total = 0;
        while (true) {
            int[] r = scanOnce();
            total += r[1];
            if (r[0] < batchSize || r[1] == 0 || System.nanoTime() >= deadline) {
                return total;
            }
        }
    }

    /** @return {candidates, closed} */
    private int[] scanOnce() {
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
        return new int[]{expired.size(), closed};
    }
}
