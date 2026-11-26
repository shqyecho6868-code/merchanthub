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
 * Inventory reservation reconciliation (E3, extended from B3-下's dangling-reservation scan): reservations still RESERVED after older-than
 * are settled according to the order's real status — normally the ORDER_PAID / ORDER_CLOSED event would have settled them long ago; if one is still hanging, the event was lost or went to the DLQ.
 * <table>
 *   <tr><th>Order status</th><th>Action</th><th>Typical cause</th></tr>
 *   <tr><td>Missing</td><td>Release (DANGLING)</td><td>Reservation succeeded, order placement failed (Feign read timeout / order insert failed)</td></tr>
 *   <tr><td>Closed 50</td><td>Release (RECON_CLOSED)</td><td>ORDER_CLOSED event lost / in DLQ</td></tr>
 *   <tr><td>Paid 20 / Shipped 30 / Completed 40</td><td>Confirm deduction</td><td>ORDER_PAID event lost / in DLQ</td></tr>
 *   <tr><td>Pending payment 10</td><td>Leave it</td><td>Order lifecycle not over yet</td></tr>
 * </table>
 * Safety preconditions (B3-下 notes section 4):
 * <ol>
 *   <li>The reservation is old enough (30 minutes by default, far beyond the order transaction timeout and the 15-minute payment window): if the order can't be found, it really doesn't exist</li>
 *   <li>Query failure ≠ missing: if order times out / is down, skip the whole round — better to return stock late than wrongly</li>
 *   <li>Both confirm and release go through the reservation CAS (WHERE status = 10): racing with a late event or another instance takes effect only once</li>
 * </ol>
 * Scheduled by XXL-Job's reservationReconJob (it was @Scheduled before E3).
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

    public record Result(int released, int confirmed) {
        public int total() {
            return released + confirmed;
        }
    }

    /**
     * The scan itself spans all merchants and runs as SYSTEM (Feign calls to order also carry X-Role: SYSTEM);
     * when actually settling an order it switches to <b>that order's merchant</b>, so SQL carries tenant_id, and automatically returns to SYSTEM afterwards.
     */
    public Result reconcile() {
        return TenantContext.callAsSystem(this::reconcileOnce);
    }

    private Result reconcileOnce() {
        List<InventoryReservation> stale = reservationMapper.selectStaleReserved(olderThan.toSeconds(), batchSize);
        if (stale.isEmpty()) {
            return new Result(0, 0);
        }
        List<Long> orderNos = stale.stream().map(InventoryReservation::getOrderNo).toList();
        Map<Long, Integer> statuses;
        try {
            BaseResponse<Map<Long, Integer>> resp = orderClient.statuses(orderNos);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("Reservation recon: order query returned an error, skipping this round resp={}", resp);
                return new Result(0, 0);
            }
            statuses = resp.getData();
        } catch (RuntimeException e) {
            // "Can't look it up" ≠ "doesn't exist": if we treated an order timeout / outage as "doesn't exist" and released, stock of orders being paid would be returned → oversell
            log.warn("Reservation recon: order query failed, skipping this round candidates={}", orderNos.size(), e);
            return new Result(0, 0);
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
        return new Result(released, confirmed);
    }
}
