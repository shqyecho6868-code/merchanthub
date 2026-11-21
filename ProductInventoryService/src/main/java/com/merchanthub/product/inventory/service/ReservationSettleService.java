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
 * 预占的两种结局：确认扣减（订单已支付）/ 释放（订单关闭、悬空预占）。
 * <p>
 * 每条预占记录先 CAS（RESERVED → CONFIRMED / RELEASED），影响 1 行才去改库存，两步在同一个本地事务里。
 * 所以重复消息、确认与释放并发，库存都只会被改一次 —— 不需要额外的 consumed_event 去重表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationSettleService {

    private final InventoryReservationMapper reservationMapper;
    private final InventoryMapper inventoryMapper;
    private final TransactionTemplate transactionTemplate;

    /** @return 本次真正确认的记录条数（0 = 重复消息或已被释放，什么也没改） */
    public int confirm(long orderNo, long tenantId) {
        return settle(orderNo, tenantId, ReservationStatus.CONFIRMED, "确认扣减");
    }

    /** @return 本次真正释放的记录条数（0 = 重复消息或已被确认，什么也没改） */
    public int release(long orderNo, long tenantId, String reason) {
        return settle(orderNo, tenantId, ReservationStatus.RELEASED, "释放(" + reason + ")");
    }

    private int settle(long orderNo, long tenantId, ReservationStatus target, String action) {
        Integer n = transactionTemplate.execute(s -> {
            // 和预占一样按 skuId 升序加库存行锁，避免与并发的预占 / 释放交叉等待形成死锁
            List<InventoryReservation> rows = reservationMapper.selectByOrderNo(orderNo).stream()
                    .sorted(Comparator.comparing(InventoryReservation::getSkuId)).toList();
            int changed = 0;
            for (InventoryReservation r : rows) {
                if (!Objects.equals(r.getTenantId(), tenantId)) {
                    // 事件里的商家和预占记录不一致：不是重试能解决的，跳过并告警，绝不跨租户改库存
                    log.error("预占记录商家不符，跳过 {} orderNo={} 事件tenantId={} 记录tenantId={}",
                            action, orderNo, tenantId, r.getTenantId());
                    continue;
                }
                if (reservationMapper.casStatus(r.getId(), ReservationStatus.RESERVED.getCode(), target.getCode()) != 1) {
                    log.info("【库存幂等】预占记录已不是 RESERVED（当前 {}），{} 跳过 orderNo={} skuId={} tenantId={}",
                            r.getStatus(), action, orderNo, r.getSkuId(), tenantId);
                    continue;
                }
                int updated = target == ReservationStatus.CONFIRMED
                        ? inventoryMapper.confirm(tenantId, r.getSkuId(), r.getQuantity())
                        : inventoryMapper.release(tenantId, r.getSkuId(), r.getQuantity());
                if (updated != 1) {
                    // reserved 不够减：库存数据已经不一致。抛异常让整个事务（含上面的记录 CAS）回滚，消息稍后重投并告警
                    throw new IllegalStateException("库存 reserved 不足以" + action + " orderNo=" + orderNo
                            + " skuId=" + r.getSkuId() + " qty=" + r.getQuantity());
                }
                changed++;
            }
            return changed;
        });
        int changed = n == null ? 0 : n;
        if (changed > 0) {
            log.info("库存{}完成 orderNo={} tenantId={} 记录数={}", action, orderNo, tenantId, changed);
        }
        return changed;
    }
}
