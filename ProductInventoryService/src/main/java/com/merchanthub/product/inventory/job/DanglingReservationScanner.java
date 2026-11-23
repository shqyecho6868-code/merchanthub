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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 悬空预占补偿：库存占上了、订单却没建成（Feign 读超时 / 落单失败 / MQ 不可用导致下单失败），
 * 这些预占永远等不到 ORDER_PAID / ORDER_CLOSED，必须按预占记录主动释放。
 * <p>
 * 安全释放的三个条件（B3-下笔记第 4 节）：
 * <ol>
 *   <li>预占足够老（默认 30 分钟：远大于下单事务的 5 秒超时，也大于 15 分钟支付时限）：这时查不到订单，订单以后也不会出现</li>
 *   <li>order 明确回答"不存在"：查询失败（超时 / 宕机）≠ 不存在，跳过等下一轮 —— 宁可晚还，不能错还</li>
 *   <li>订单存在就不碰：交给订单自己的生命周期（支付 → 确认；超时 → 释放）</li>
 * </ol>
 * 多实例同时扫描是安全的：释放走预占记录 CAS，同一条只会被释放一次。以后引入 XXL-Job 时迁移（同 outbox 扫描）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanglingReservationScanner {

    private final InventoryReservationMapper reservationMapper;
    private final OrderClient orderClient;
    private final ReservationSettleService settleService;

    @Value("${merchanthub.inventory.dangling-scan.enabled:true}")
    private boolean enabled;
    @Value("${merchanthub.inventory.dangling-scan.older-than:30m}")
    private Duration olderThan;
    @Value("${merchanthub.inventory.dangling-scan.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${merchanthub.inventory.dangling-scan.interval-ms:60000}")
    public void scheduledScan() {
        if (enabled) {
            scan();
        }
    }

    /**
     * 扫描本身跨所有商家，用 SYSTEM 身份（Feign 查 order 时也带着 X-Role: SYSTEM）；
     * 真正释放某一单时切到<b>那一单的商家</b>，释放的 SQL 带上 tenant_id，处理完自动回到 SYSTEM。
     *
     * @return 本轮释放的订单数
     */
    public int scan() {
        return TenantContext.callAsSystem(this::scanOnce);
    }

    private int scanOnce() {
        List<InventoryReservation> stale = reservationMapper.selectStaleReserved(olderThan.toSeconds(), batchSize);
        if (stale.isEmpty()) {
            return 0;
        }
        List<Long> orderNos = stale.stream().map(InventoryReservation::getOrderNo).toList();
        Set<Long> existing;
        try {
            BaseResponse<List<Long>> resp = orderClient.existing(orderNos);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("悬空预占扫描：查询订单返回异常，本轮跳过 resp={}", resp);
                return 0;
            }
            existing = new HashSet<>(resp.getData());
        } catch (RuntimeException e) {
            // 查不到 ≠ 不存在：order 超时 / 宕机时如果当成"不存在"释放，正在支付的订单库存就被还回去了 → 超卖
            log.warn("悬空预占扫描：查询订单失败，本轮跳过 候选数={}", orderNos.size(), e);
            return 0;
        }

        int released = 0;
        for (InventoryReservation r : stale) {
            if (existing.contains(r.getOrderNo())) {
                continue;   // 订单存在：由支付 / 超时关单了结，这里不碰
            }
            int n = TenantContext.callAs(TenantInfo.systemOf(r.getTenantId()),
                    () -> settleService.release(r.getOrderNo(), r.getTenantId(), "DANGLING"));
            if (n > 0) {
                log.warn("【悬空预占】订单不存在，已释放预占 orderNo={} tenantId={}", r.getOrderNo(), r.getTenantId());
                released++;
            }
        }
        log.info("悬空预占扫描完成 候选={} 订单存在={} 释放={}", stale.size(), existing.size(), released);
        return released;
    }
}
