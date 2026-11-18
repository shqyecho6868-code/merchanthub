package com.merchanthub.product.inventory.service;

import com.merchanthub.product.inventory.model.dto.ReserveRequest;

public interface InventoryReservationService {

    /**
     * 为一个订单预占多个 SKU 的库存：全部成功才提交，任何一个失败整体回滚。
     * 幂等：同一 orderNo 且内容一致的重复请求直接返回成功；内容不一致抛 RESERVATION_CONFLICT。
     *
     * @throws com.merchanthub.common.exception.BusinessException INVENTORY_INSUFFICIENT / RESERVATION_CONFLICT
     */
    void reserve(ReserveRequest request);
}
