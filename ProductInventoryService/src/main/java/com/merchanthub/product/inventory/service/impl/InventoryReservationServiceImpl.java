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
 * 多 SKU 库存预占：一个订单的所有 SKU 在一个本地事务里预占，全占或全不占。
 * 思路与原因见 docs/notes/B1-下单与库存预占.md 第 7 节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private final InventoryMapper inventoryMapper;
    private final InventoryReservationMapper reservationMapper;
    /** 编程式事务：外层要能 catch 住“事务回滚之后”的唯一键冲突，见笔记 7.3 */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void reserve(ReserveRequest request) {
        List<ReserveRequest.Item> items = request.getItems();

        // 同一个 skuId 出现两次，第二行插入会撞上第一行的唯一键，被误判成“重复请求”
        long distinctSkus = items.stream().map(ReserveRequest.Item::getSkuId).distinct().count();
        ThrowUtils.throwIf(distinctSkus != items.size(), ErrorCode.PARAMS_ERROR, "同一订单内 skuId 不能重复");

        // 所有事务按 skuId 升序加行锁，避免交叉等待形成死锁
        List<ReserveRequest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(ReserveRequest.Item::getSkuId))
                .toList();

        // 快速路径：最常见的重试（上次其实已成功）在这里就结束，不进事务、不抢行锁
        List<InventoryReservation> existing = reservationMapper.selectByOrderNo(request.getOrderNo());
        if (!existing.isEmpty()) {
            checkSameAsExisting(request, existing);
            log.info("重复预占请求，幂等返回成功 orderNo={}", request.getOrderNo());
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> reserveAllInTransaction(request, sorted));
        } catch (DuplicateKeyException e) {
            // 并发的重复请求：两个请求都通过了快速路径，后插入的撞唯一键，它的事务已整体回滚（包括已扣的库存）。
            // 这里已在事务外，重新查到的是对方提交后的记录
            checkSameAsExisting(request, reservationMapper.selectByOrderNo(request.getOrderNo()));
            log.info("并发重复预占请求，幂等返回成功 orderNo={}", request.getOrderNo());
        }
    }

    /**
     * 在一个事务里逐个 SKU：先插预占记录（幂等闸门），再条件更新库存。
     * 任何一个 SKU 失败就抛异常，TransactionTemplate 回滚整个事务。
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
            // 影响 0 行 = WHERE 条件不成立（库存不足 / 不属于该租户）。MySQL 不会报错，必须自己抛异常：
            // 否则这一轮插的预占记录和前面几个 SKU 的扣减都会被提交 → 部分预占，且记录与库存不一致。
            // （B1 code review 练习：这里曾故意写成 rows < 0，永远不成立，见 interview-qa）
            if (rows == 0) {
                throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "库存不足 skuId=" + item.getSkuId());
            }
        }
    }

    /**
     * 已有记录和本次请求是否“同一次预占”：tenantId 相同、SKU 集合相同、每个 SKU 数量相同。
     * 一致 → 幂等成功；不一致 → 同一个订单号想占不同的东西，是调用方的 bug，拒绝。
     */
    private void checkSameAsExisting(ReserveRequest request, List<InventoryReservation> existing) {
        boolean sameTenant = existing.stream().allMatch(r -> request.getTenantId().equals(r.getTenantId()));
        Map<Long, Integer> wanted = request.getItems().stream()
                .collect(Collectors.toMap(ReserveRequest.Item::getSkuId, ReserveRequest.Item::getQuantity));
        Map<Long, Integer> reserved = existing.stream()
                .collect(Collectors.toMap(InventoryReservation::getSkuId, InventoryReservation::getQuantity));
        if (!sameTenant || !wanted.equals(reserved)) {
            log.warn("同一订单号的预占内容不一致 orderNo={} wanted={} reserved={}", request.getOrderNo(), wanted, reserved);
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
        }
    }
}
