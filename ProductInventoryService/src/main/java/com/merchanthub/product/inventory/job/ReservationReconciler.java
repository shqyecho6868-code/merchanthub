package com.merchanthub.product.inventory.job;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.product.inventory.client.OrderClient;
import com.merchanthub.product.inventory.mapper.InventoryReservationMapper;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import com.merchanthub.product.inventory.service.ReservationSettleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Inventory reservation reconciliation (extends the dangling-reservation scan): reservations still RESERVED after older-than
 * are settled according to the order's real status — normally the ORDER_PAID / ORDER_CLOSED event would have settled them long ago; if one is still hanging, the event was lost or went to the DLQ.
 * <table>
 *   <tr><th>Order status</th><th>Action</th><th>Typical cause</th></tr>
 *   <tr><td>Missing</td><td>Release (DANGLING)</td><td>Reservation succeeded, order placement failed (Feign read timeout / order insert failed)</td></tr>
 *   <tr><td>Closed 50</td><td>Release (RECON_CLOSED)</td><td>ORDER_CLOSED event lost / in DLQ</td></tr>
 *   <tr><td>Paid 20 / Shipped 30 / Completed 40</td><td>Confirm deduction</td><td>ORDER_PAID event lost / in DLQ</td></tr>
 *   <tr><td>Pending payment 10</td><td>Leave it</td><td>Order lifecycle not over yet</td></tr>
 * </table>
 * Safety preconditions:
 * <ol>
 *   <li>The reservation is old enough (30 minutes by default, far beyond the order transaction timeout and the 15-minute payment window): if the order can't be found, it really doesn't exist</li>
 *   <li>Query failure ≠ missing: if order times out / is down, skip the whole round — better to return stock late than wrongly</li>
 *   <li>Both confirm and release go through the reservation CAS (WHERE status = 10): racing with a late event or another instance takes effect only once</li>
 * </ol>
 * Scheduled by XXL-Job's reservationReconJob (formerly @Scheduled).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReconciler {

    static final int PENDING = 10;
    static final int CLOSED = 50;

    private final InventoryReservationMapper reservationMapper;
    private final OrderClient orderClient;
    private final ReservationSettleService settleService;

    @Value("${merchanthub.inventory.reservation-recon.older-than:30m}")
    private Duration olderThan;
    @Value("${merchanthub.inventory.reservation-recon.batch-size:100}")
    private int batchSize;

    public record Result(int released, int confirmed, int candidates) {
        public Result(int released, int confirmed) {
            this(released, confirmed, 0);
        }

        public int total() {
            return released + confirmed;
        }
    }

    /**
     * The scan itself spans all merchants and runs as SYSTEM (Feign calls to order also carry X-Role: SYSTEM);
     * when actually settling an order it switches to <b>that order's merchant</b>, so SQL carries tenant_id, and automatically returns to SYSTEM afterwards.
     */
    public Result reconcile() {
        return TenantContext.callAsSystem(this::reconcileUntilDone);
    }

    /** One call keeps going for at most this long (XXL-Job executor timeout of reservationReconJob is 120s) */
    @Value("${merchanthub.inventory.reservation-recon.time-budget:90s}")
    private Duration timeBudget = Duration.ofSeconds(90);

    /**
     * Catch-up: 100 reservations every 5 minutes would need hours for thousands of stuck ones, so keep paging (keyset on order_no)
     * while a page is full. The cursor always moves forward, so the loop ends; a page where order couldn't be queried (candidates = -1) stops it
     */
    private Result reconcileUntilDone() {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        int released = 0;
        int confirmed = 0;
        long cursor = 0;
        while (true) {
            List<InventoryReservation> page = reservationMapper.selectStaleReserved(olderThan.toSeconds(), cursor, batchSize);
            Result r = reconcilePage(page);
            released += r.released();
            confirmed += r.confirmed();
            if (r.candidates() < batchSize || System.nanoTime() >= deadline) {
                return new Result(released, confirmed);
            }
            cursor = page.get(page.size() - 1).getOrderNo();
        }
    }

    private Result reconcilePage(List<InventoryReservation> stale) {
        if (stale.isEmpty()) {
            return new Result(0, 0);
        }
        List<Long> orderNos = stale.stream().map(InventoryReservation::getOrderNo).toList();
        Map<Long, Integer> statuses;
        try {
            BaseResponse<Map<Long, Integer>> resp = orderClient.statuses(orderNos);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("Reservation recon: order query returned an error, skipping this round resp={}", resp);
                return new Result(0, 0, -1);
            }
            statuses = resp.getData();
        } catch (RuntimeException e) {
            // "Can't look it up" ≠ "doesn't exist": if we treated an order timeout / outage as "doesn't exist" and released, stock of orders being paid would be returned → oversell
            log.warn("Reservation recon: order query failed, skipping this round candidates={}", orderNos.size(), e);
            return new Result(0, 0, -1);
        }

        int released = 0;
        int confirmed = 0;
        for (InventoryReservation r : stale) {
            Integer status = statuses.get(r.getOrderNo());
            if (status != null && status == PENDING) {
                continue;   // The order is still awaiting payment: settled by payment / timeout close, don't touch it here
            }
            TenantInfo tenant = TenantInfo.systemOf(r.getTenantId());
            if (status == null || status == CLOSED) {
                String reason = status == null ? "DANGLING" : "RECON_CLOSED";
                int n = TenantContext.callAs(tenant, () -> settleService.release(r.getOrderNo(), r.getTenantId(), reason));
                if (n > 0) {
                    released++;
                    log.warn("[Reservation recon] order {}, reservation was never released, released now orderNo={} tenantId={}",
                            status == null ? "missing" : "closed", r.getOrderNo(), r.getTenantId());
                }
            } else {
                int n = TenantContext.callAs(tenant, () -> settleService.confirm(r.getOrderNo(), r.getTenantId()));
                if (n > 0) {
                    confirmed++;
                    log.warn("[Reservation recon] order already paid (status {}), reservation was never confirmed, deduction confirmed now orderNo={} tenantId={}",
                            status, r.getOrderNo(), r.getTenantId());
                }
            }
        }
        log.info("Reservation recon done candidates={} released={} confirmed={}", stale.size(), released, confirmed);
        return new Result(released, confirmed, stale.size());
    }
}
