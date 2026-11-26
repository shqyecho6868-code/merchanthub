package com.merchanthub.product.inventory.service.impl;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.exception.ThrowUtils;
import com.merchanthub.product.inventory.mapper.InventoryMapper;
import com.merchanthub.product.inventory.mapper.InventoryReservationMapper;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import com.merchanthub.product.inventory.model.enums.ReservationStatus;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Multi-SKU stock reservation: all SKUs of an order are reserved in one local transaction — all or nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private final InventoryMapper inventoryMapper;
    private final InventoryReservationMapper reservationMapper;
    /** Programmatic transaction: the outer layer must be able to catch the unique-key conflict that surfaces "after the transaction rolled back" */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void reserve(ReserveRequest request) {
        List<ReserveRequest.Item> items = request.getItems();

        // If the same skuId appears twice, the second row insert hits the first row's unique key and is misjudged as a "duplicate request"
        long distinctSkus = items.stream().map(ReserveRequest.Item::getSkuId).distinct().count();
        ThrowUtils.throwIf(distinctSkus != items.size(), ErrorCode.PARAMS_ERROR, "skuId must not repeat within one order");

        // All transactions take row locks in ascending skuId order, to avoid circular waits that cause deadlocks
        List<ReserveRequest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(ReserveRequest.Item::getSkuId))
                .toList();

        // Fast path: the most common retry (the previous attempt actually succeeded) ends here, without entering a transaction or contending for row locks
        List<InventoryReservation> existing = reservationMapper.selectByOrderNo(request.getOrderNo());
        if (!existing.isEmpty()) {
            checkSameAsExisting(request, existing);
            log.info("Duplicate reservation request, returning success idempotently orderNo={}", request.getOrderNo());
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> reserveAllInTransaction(request, sorted));
        } catch (DuplicateKeyException e) {
            // Concurrent duplicate request: both passed the fast path; the later insert hit the unique key and its transaction was rolled back entirely (including deducted stock).
            // We're outside the transaction here, so the re-query sees the record the other request committed
            checkSameAsExisting(request, reservationMapper.selectByOrderNo(request.getOrderNo()));
            log.info("Concurrent duplicate reservation request, returning success idempotently orderNo={}", request.getOrderNo());
        }
    }

    /**
     * Within one transaction, SKU by SKU: first insert the reservation record (idempotency gate), then conditionally update stock.
     * If any SKU fails we throw, and TransactionTemplate rolls back the whole transaction.
     */
    private void reserveAllInTransaction(ReserveRequest request, List<ReserveRequest.Item> sortedItems) {
        for (ReserveRequest.Item item : sortedItems) {
            InventoryReservation reservation = new InventoryReservation();
            reservation.setTenantId(request.getTenantId());
            reservation.setOrderNo(request.getOrderNo());
            reservation.setSkuId(item.getSkuId());
            reservation.setQuantity(item.getQuantity());
            reservation.setStatus(ReservationStatus.RESERVED.getCode());
            reservationMapper.insert(reservation);

            int rows = inventoryMapper.tryReserve(request.getTenantId(), item.getSkuId(), item.getQuantity());
            // 0 rows affected = the WHERE condition failed (insufficient stock / not this tenant's). MySQL doesn't raise an error, so we must throw ourselves:
            // otherwise this round's reservation record and the earlier SKUs' deductions would all be committed → partial reservation, with records and stock out of sync.
            // (rows < 0 would never hold here, so the check must be rows == 0)
            if (rows == 0) {
                throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "Insufficient stock skuId=" + item.getSkuId());
            }
        }
    }

    /**
     * Whether the existing records and this request are "the same reservation": same tenantId, same SKU set, same quantity per SKU.
     * Identical → idempotent success; different → the same order number wants to reserve something else, which is a caller bug, so reject.
     */
    private void checkSameAsExisting(ReserveRequest request, List<InventoryReservation> existing) {
        boolean sameTenant = existing.stream().allMatch(r -> request.getTenantId().equals(r.getTenantId()));
        Map<Long, Integer> wanted = request.getItems().stream()
                .collect(Collectors.toMap(ReserveRequest.Item::getSkuId, ReserveRequest.Item::getQuantity));
        Map<Long, Integer> reserved = existing.stream()
                .collect(Collectors.toMap(InventoryReservation::getSkuId, InventoryReservation::getQuantity));
        if (!sameTenant || !wanted.equals(reserved)) {
            log.warn("Reservation content mismatch for the same order number orderNo={} wanted={} reserved={}", request.getOrderNo(), wanted, reserved);
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
        }
    }
}
