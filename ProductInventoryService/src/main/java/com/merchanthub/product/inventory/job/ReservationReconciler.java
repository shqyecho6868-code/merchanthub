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
 * 库存预占对账（E3，由 B3-下的悬空预占扫描扩展而来）：预占记录超过 older-than 仍是 RESERVED，
 * 按订单的真实状态把它了结 —— 正常情况下 ORDER_PAID / ORDER_CLOSED 事件早就了结它了，还挂着说明事件丢了或进了死信。
 * <table>
 *   <tr><th>订单状态</th><th>处理</th><th>典型原因</th></tr>
 *   <tr><td>不存在</td><td>释放（DANGLING）</td><td>预占成功、下单失败（Feign 读超时 / 落单失败）</td></tr>
 *   <tr><td>已关闭 50</td><td>释放（RECON_CLOSED）</td><td>ORDER_CLOSED 事件丢失 / 进死信</td></tr>
 *   <tr><td>已支付 20 / 已发货 30 / 已完成 40</td><td>确认扣减</td><td>ORDER_PAID 事件丢失 / 进死信</td></tr>
 *   <tr><td>待支付 10</td><td>不动</td><td>订单生命周期还没结束</td></tr>
 * </table>
 * 安全前提（B3-下笔记第 4 节）：
 * <ol>
 *   <li>预占足够老（默认 30 分钟，远大于下单事务超时和 15 分钟支付时限）：查不到订单就是真没有</li>
 *   <li>查询失败 ≠ 不存在：order 超时 / 宕机就跳过整轮 —— 宁可晚还，不能错还</li>
 *   <li>确认 / 释放都走预占记录 CAS（WHERE status = 10）：和迟到的事件、另一个实例并发执行都只生效一次</li>
 * </ol>
 * 由 XXL-Job 的 reservationReconJob 调度（E3 前是 @Scheduled）。
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
     * 扫描本身跨所有商家，用 SYSTEM 身份（Feign 查 order 时也带着 X-Role: SYSTEM）；
     * 真正了结某一单时切到<b>那一单的商家</b>，SQL 带上 tenant_id，处理完自动回到 SYSTEM。
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
                log.warn("预占对账：查询订单返回异常，本轮跳过 resp={}", resp);
                return new Result(0, 0);
            }
            statuses = resp.getData();
        } catch (RuntimeException e) {
            // 查不到 ≠ 不存在：order 超时 / 宕机时如果当成"不存在"释放，正在支付的订单库存就被还回去了 → 超卖
            log.warn("预占对账：查询订单失败，本轮跳过 候选数={}", orderNos.size(), e);
            return new Result(0, 0);
        }

        int released = 0;
        int confirmed = 0;
        for (InventoryReservation r : stale) {
            Integer status = statuses.get(r.getOrderNo());
            if (status != null && status == PENDING) {
                continue;   // 订单还在等支付：由支付 / 超时关单了结，这里不碰
            }
            TenantInfo tenant = TenantInfo.systemOf(r.getTenantId());
            if (status == null || status == CLOSED) {
                String reason = status == null ? "DANGLING" : "RECON_CLOSED";
                int n = TenantContext.callAs(tenant, () -> settleService.release(r.getOrderNo(), r.getTenantId(), reason));
                if (n > 0) {
                    released++;
                    log.warn("【预占对账】订单{}，预占仍未释放，已释放 orderNo={} tenantId={}",
                            status == null ? "不存在" : "已关闭", r.getOrderNo(), r.getTenantId());
                }
            } else {
                int n = TenantContext.callAs(tenant, () -> settleService.confirm(r.getOrderNo(), r.getTenantId()));
                if (n > 0) {
                    confirmed++;
                    log.warn("【预占对账】订单已支付（状态 {}），预占仍未确认，已确认扣减 orderNo={} tenantId={}",
                            status, r.getOrderNo(), r.getTenantId());
                }
            }
        }
        log.info("预占对账完成 候选={} 释放={} 确认={}", stale.size(), released, confirmed);
        return new Result(released, confirmed);
    }
}
