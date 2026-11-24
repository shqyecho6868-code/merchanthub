package com.merchanthub.product.inventory;

import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.product.ProductItSupport;
import com.merchanthub.product.ProductJobs;
import com.merchanthub.product.inventory.client.OrderClient;
import com.merchanthub.product.inventory.job.ReservationReconciler;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3：库存预占对账（B3-下悬空预占扫描的扩展）。预占挂了 30 分钟以上仍是 RESERVED，按订单真实状态了结：
 * 不存在 / 已关闭 → 释放；已支付 → 确认扣减；待支付 → 不动；查询失败 → 整轮跳过。
 */
@SpringBootTest(properties = "merchanthub.inventory.reservation-recon.older-than=30m")
class ReservationReconIT extends ProductItSupport {

    private static final long TENANT = 7L;

    @MockitoBean
    private OrderClient orderClient;
    @Autowired
    private InventoryReservationService reserveService;
    @Autowired
    private ReservationReconciler reconciler;
    @Autowired
    private ProductJobs jobs;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE inventory");
        jdbc.execute("TRUNCATE TABLE inventory_reservation");
        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (?, 101, 100, 0)", TENANT);
    }

    /** 预占一单；minutesAgo > 0 时把预占时间改到过去，模拟"占了很久" */
    private long reserve(long orderNo, int minutesAgo) {
        reserveService.reserve(new ReserveRequest(orderNo, TENANT, List.of(new ReserveRequest.Item(101L, 1))));
        if (minutesAgo > 0) {
            jdbc.update("UPDATE inventory_reservation SET created_at = NOW(3) - INTERVAL ? MINUTE WHERE order_no = ?",
                    minutesAgo, orderNo);
        }
        return orderNo;
    }

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE order_no = ?", Integer.class, orderNo);
    }

    private Map<String, Object> stock() {
        return jdbc.queryForMap("SELECT available, reserved FROM inventory WHERE sku_id = 101");
    }

    /**
     * 五种预占一次对账。初始库存 100，5 单各占 1 → available 95 / reserved 5。
     * <ul>
     *   <li>dangling：订单不存在（下单失败）→ 释放，available +1、reserved -1</li>
     *   <li><b>closed：订单已关闭但预占没释放</b>（ORDER_CLOSED 丢了）→ 释放</li>
     *   <li>paid：订单已支付但预占没确认（ORDER_PAID 丢了）→ 确认扣减，reserved -1、available 不变</li>
     *   <li>pending：订单还待支付 → 不动</li>
     *   <li>fresh：预占才几分钟 → 不是候选</li>
     * </ul>
     */
    @Test
    void staleReservations_settledByOrderStatus() {
        long dangling = reserve(1001, 40);
        long closed = reserve(1002, 40);
        long paid = reserve(1003, 40);
        long pending = reserve(1004, 40);
        long fresh = reserve(1005, 0);
        when(orderClient.statuses(any())).thenReturn(ResultUtils.success(Map.of(closed, 50, paid, 20, pending, 10)));

        jobs.reservationRecon();

        assertThat(status(dangling)).isEqualTo(30);
        assertThat(status(closed)).isEqualTo(30);
        assertThat(status(paid)).isEqualTo(20);
        assertThat(status(pending)).isEqualTo(10);
        assertThat(status(fresh)).isEqualTo(10);
        assertThat(stock()).containsEntry("available", 97).containsEntry("reserved", 2);

        // 再跑一轮：已了结的不再是候选（CAS 只生效一次），库存不会多还 / 多扣
        ReservationReconciler.Result again = reconciler.reconcile();
        assertThat(again.total()).isZero();
        assertThat(stock()).containsEntry("available", 97).containsEntry("reserved", 2);
    }

    /** 查不到 ≠ 不存在：order 超时 / 宕机时一条都不动，下一轮再说 */
    @Test
    void orderServiceDown_nothingTouched() {
        long dangling = reserve(2001, 40);
        when(orderClient.statuses(any())).thenThrow(new FeignException.ServiceUnavailable("order down",
                Request.create(Request.HttpMethod.POST, "/internal/orders/statuses", Map.of(), null,
                        StandardCharsets.UTF_8, null), null, null));

        assertThat(reconciler.reconcile().total()).isZero();
        assertThat(status(dangling)).isEqualTo(10);
        assertThat(stock()).containsEntry("available", 99).containsEntry("reserved", 1);
    }

    @Test
    void badResponse_skipsRound() {
        long dangling = reserve(2002, 40);
        doReturn(ResultUtils.error(50000, "boom")).when(orderClient).statuses(any());
        assertThat(reconciler.reconcile().total()).isZero();
        assertThat(status(dangling)).isEqualTo(10);
    }

    @Test
    void noCandidates_orderServiceNotCalled() {
        reserve(3001, 0);
        assertThat(reconciler.reconcile().total()).isZero();
        verify(orderClient, never()).statuses(any());
    }
}
