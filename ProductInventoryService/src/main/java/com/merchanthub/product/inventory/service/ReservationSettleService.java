package com.merchanthub.product.inventory.service;

import com.merchanthub.product.inventory.mapper.InventoryMapper;
import com.merchanthub.product.inventory.mapper.InventoryReservationMapper;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import com.merchanthub.product.inventory.model.enums.ReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The two outcomes of a reservation: confirm deduction (order paid) / release (order closed, dangling reservation).
 * <p>
 * Each reservation record is CASed first (RESERVED → CONFIRMED / RELEASED); only if 1 row is affected is stock changed, both steps in one local transaction.
 * So with duplicate messages or a confirm racing a release, stock is changed only once — no extra consumed_event dedup table needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationSettleService {

    private final InventoryReservationMapper reservationMapper;
    private final InventoryMapper inventoryMapper;
    private final TransactionTemplate transactionTemplate;

    /** @return number of records actually confirmed this call (0 = duplicate message or already released, nothing changed) */
    public int confirm(long orderNo, long tenantId) {
        return settle(orderNo, tenantId, ReservationStatus.CONFIRMED, "confirm deduction");
    }

    /** @return number of records actually released this call (0 = duplicate message or already confirmed, nothing changed) */
    public int release(long orderNo, long tenantId, String reason) {
        return settle(orderNo, tenantId, ReservationStatus.RELEASED, "release(" + reason + ")");
    }

    private int settle(long orderNo, long tenantId, ReservationStatus target, String action) {
        Integer n = transactionTemplate.execute(s -> {
            // Like reservation, take inventory row locks in ascending skuId order to avoid circular waits (deadlocks) with concurrent reserves / releases
            List<InventoryReservation> rows = reservationMapper.selectByOrderNo(orderNo).stream()
                    .sorted(Comparator.comparing(InventoryReservation::getSkuId)).toList();
            int changed = 0;
            for (InventoryReservation r : rows) {
                if (!Objects.equals(r.getTenantId(), tenantId)) {
                    // The merchant in the event doesn't match the reservation record: retrying won't fix it, so skip and alert — never modify stock across tenants
                    log.error("Reservation record merchant mismatch, skipping {} orderNo={} eventTenantId={} recordTenantId={}",
                            action, orderNo, tenantId, r.getTenantId());
                    continue;
                }
                if (reservationMapper.casStatus(r.getId(), ReservationStatus.RESERVED.getCode(), target.getCode()) != 1) {
                    log.info("[Inventory idempotency] reservation record is no longer RESERVED (currently {}), {} skipped orderNo={} skuId={} tenantId={}",
                            r.getStatus(), action, orderNo, r.getSkuId(), tenantId);
                    continue;
                }
                int updated = target == ReservationStatus.CONFIRMED
                        ? inventoryMapper.confirm(tenantId, r.getSkuId(), r.getQuantity())
                        : inventoryMapper.release(tenantId, r.getSkuId(), r.getQuantity());
                if (updated != 1) {
                    // Not enough reserved to subtract: inventory data is already inconsistent. Throw so the whole transaction (including the record CAS above) rolls back; the message is redelivered later and alerted
                    throw new IllegalStateException("Inventory reserved insufficient to " + action + " orderNo=" + orderNo
                            + " skuId=" + r.getSkuId() + " qty=" + r.getQuantity());
                }
                changed++;
            }
            return changed;
        });
        int changed = n == null ? 0 : n;
        if (changed > 0) {
            log.info("Inventory {} done orderNo={} tenantId={} records={}", action, orderNo, tenantId, changed);
        }
        return changed;
    }
}
