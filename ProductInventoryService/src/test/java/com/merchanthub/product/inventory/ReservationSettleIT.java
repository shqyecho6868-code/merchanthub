package com.merchanthub.product.inventory;

import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import com.merchanthub.product.inventory.service.ReservationSettleService;
import com.merchanthub.product.ProductItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirm deduction / release: the reservation record CAS is the inventory side's idempotency gate.
 * Everything here verifies "duplicates / concurrency still change stock only once", per B3-下 notes section 2.
 */
@SpringBootTest
class ReservationSettleIT extends ProductItSupport {

    private static final long TENANT = 7L;
    private static final AtomicLong ORDER_NO = new AtomicLong(2_000_000);

    @Autowired
    private InventoryReservationService reserveService;
    @Autowired
    private ReservationSettleService settleService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE inventory");
        jdbc.execute("TRUNCATE TABLE inventory_reservation");
        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (?, 101, 10, 0), (?, 102, 10, 0)",
                TENANT, TENANT);
    }

    private long reserve(int q101, int q102) {
        long orderNo = ORDER_NO.incrementAndGet();
        reserveService.reserve(new ReserveRequest(orderNo, TENANT,
                List.of(new ReserveRequest.Item(101L, q101), new ReserveRequest.Item(102L, q102))));
        return orderNo;
    }

    private int[] stock(long skuId) {
        return jdbc.queryForObject("SELECT available, reserved FROM inventory WHERE sku_id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, skuId);
    }

    private List<Integer> statuses(long orderNo) {
        return jdbc.queryForList("SELECT status FROM inventory_reservation WHERE order_no = ? ORDER BY sku_id",
                Integer.class, orderNo);
    }

    @Test
    void release_returnsStockOnce_evenIfRepeated() {
        long orderNo = reserve(2, 3);

        assertThat(settleService.release(orderNo, TENANT, "PAY_TIMEOUT")).isEqualTo(2);
        // The same ORDER_CLOSED delivered again: the record is already RELEASED, the CAS affects 0 rows, stock isn't added back again
        assertThat(settleService.release(orderNo, TENANT, "PAY_TIMEOUT")).isZero();

        assertThat(stock(101)).containsExactly(10, 0);
        assertThat(stock(102)).containsExactly(10, 0);
        assertThat(statuses(orderNo)).containsOnly(30);
    }

    @Test
    void confirm_deductsReservedOnce_availableUnchanged() {
        long orderNo = reserve(2, 3);

        assertThat(settleService.confirm(orderNo, TENANT)).isEqualTo(2);
        assertThat(settleService.confirm(orderNo, TENANT)).isZero();

        assertThat(stock(101)).containsExactly(8, 0);   // 8 available, 2 sold (no longer in reserved)
        assertThat(stock(102)).containsExactly(7, 0);
        assertThat(statuses(orderNo)).containsOnly(20);
    }

    /** Terminal states are mutually exclusive: for a confirmed reservation (paid, goods sold), a late release message can't return the stock */
    @Test
    void releaseAfterConfirm_noop() {
        long orderNo = reserve(2, 3);
        settleService.confirm(orderNo, TENANT);

        assertThat(settleService.release(orderNo, TENANT, "DANGLING")).isZero();
        assertThat(stock(101)).containsExactly(8, 0);
        assertThat(statuses(orderNo)).containsOnly(20);
    }

    @Test
    void otherTenant_cannotRelease() {
        long orderNo = reserve(2, 3);
        assertThat(settleService.release(orderNo, 8L, "PAY_TIMEOUT")).isZero();
        assertThat(stock(101)).containsExactly(8, 2);
        assertThat(statuses(orderNo)).containsOnly(10);
    }

    /**
     * Concurrency: 10 threads release and 10 threads confirm the same order at once (simulating duplicate messages + an upstream bug sending both outcomes).
     * Only one thread's CAS succeeds per reservation record, stock changes only once, and the two SKUs' outcomes are each unique — never "both returned and deducted".
     */
    @Test
    void concurrentReleaseAndConfirm_eachRecordSettledOnce() throws Exception {
        long orderNo = reserve(2, 3);
        ConcurrentLinkedQueue<Integer> changed = new ConcurrentLinkedQueue<>();
        List<Throwable> errors = InventoryReservationIT.runConcurrently(20, i -> () -> {
            changed.add(i % 2 == 0 ? settleService.release(orderNo, TENANT, "PAY_TIMEOUT")
                    : settleService.confirm(orderNo, TENANT));
            return null;
        });

        assertThat(errors).isEmpty();
        assertThat(changed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);   // two records, each processed once
        for (long sku : new long[]{101, 102}) {
            int[] s = stock(sku);
            assertThat(s[1]).isZero();                                    // all reservations settled
            assertThat(s[0]).isIn(10, sku == 101 ? 8 : 7);                // either returned or sold, never over-returned
        }
    }
}
