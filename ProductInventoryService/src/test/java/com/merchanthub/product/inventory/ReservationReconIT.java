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
import org.springframework.test.util.ReflectionTestUtils;

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
 * Inventory reservation reconciliation (extension of the dangling-reservation scan). Reservations held for 30+ minutes and still RESERVED are settled by the order's real status:
 * missing / closed → release; paid → confirm deduction; pending payment → leave it; query failure → skip the whole round.
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

    /** Reserve one order; when minutesAgo > 0, move the reservation time into the past to simulate "held for a long time" */
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
     * Reconcile five kinds of reservations in one run. Initial stock 100, 5 orders reserve 1 each → available 95 / reserved 5.
     * <ul>
     *   <li>dangling: order doesn't exist (order placement failed) → release, available +1, reserved -1</li>
     *   <li><b>closed: order closed but the reservation wasn't released</b> (ORDER_CLOSED lost) → release</li>
     *   <li>paid: order paid but the reservation wasn't confirmed (ORDER_PAID lost) → confirm deduction, reserved -1, available unchanged</li>
     *   <li>pending: order still pending payment → leave it</li>
     *   <li>fresh: reserved only a few minutes ago → not a candidate</li>
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

        // Run another round: settled ones are no longer candidates (the CAS takes effect only once), so stock isn't over-returned / over-deducted
        ReservationReconciler.Result again = reconciler.reconcile();
        assertThat(again.total()).isZero();
        assertThat(stock()).containsEntry("available", 97).containsEntry("reserved", 2);
    }

    /** "Can't look it up" ≠ "doesn't exist": when order times out / is down, nothing is touched; try again next round */
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

    /**
     * Catch-up: stuck reservations behind a full page of orders that are legitimately still pending.
     * Batch size 3; orders 4001-4003 are pending (left alone), 4004-4006 are closed (ORDER_CLOSED lost).
     * Before keyset paging, every round re-read the same first page of 3 pending orders, so the closed ones were never released;
     * now one call pages past them and releases all three.
     */
    @Test
    void fullPageOfPendingOrders_doesNotHideStuckOnesBehindIt() {
        Map<Long, Integer> orderStatus = new java.util.HashMap<>();
        for (long n = 4001; n <= 4006; n++) {
            reserve(n, 40);
            orderStatus.put(n, n <= 4003 ? 10 : 50);
        }
        when(orderClient.statuses(any())).thenAnswer(inv -> {
            Map<Long, Integer> m = new java.util.HashMap<>();
            for (Long no : inv.<List<Long>>getArgument(0)) {
                m.put(no, orderStatus.get(no));
            }
            return ResultUtils.success(m);
        });
        Object old = ReflectionTestUtils.getField(reconciler, "batchSize");
        ReflectionTestUtils.setField(reconciler, "batchSize", 3);
        try {
            ReservationReconciler.Result r = reconciler.reconcile();
            assertThat(r.released()).isEqualTo(3);
        } finally {
            ReflectionTestUtils.setField(reconciler, "batchSize", old);
        }
        for (long n = 4001; n <= 4003; n++) {
            assertThat(status(n)).isEqualTo(10);
        }
        for (long n = 4004; n <= 4006; n++) {
            assertThat(status(n)).isEqualTo(30);
        }
        assertThat(stock()).containsEntry("available", 97).containsEntry("reserved", 3);
    }
}
