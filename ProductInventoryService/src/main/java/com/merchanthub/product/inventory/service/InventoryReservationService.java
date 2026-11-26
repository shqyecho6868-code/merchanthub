package com.merchanthub.product.inventory.service;

import com.merchanthub.product.inventory.model.dto.ReserveRequest;

public interface InventoryReservationService {

    /**
     * Reserve stock for multiple SKUs of one order: commit only if all succeed; any failure rolls back everything.
     * Idempotent: a repeated request with the same orderNo and identical content returns success directly; differing content throws RESERVATION_CONFLICT.
     *
     * @throws com.merchanthub.common.exception.BusinessException INVENTORY_INSUFFICIENT / RESERVATION_CONFLICT
     */
    void reserve(ReserveRequest request);
}
