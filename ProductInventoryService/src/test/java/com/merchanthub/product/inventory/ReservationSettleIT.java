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
 * 确认扣减 / 释放：预占记录 CAS 是库存侧的幂等闸门。
 * 这里验证的都是"重复 / 并发也只改一次库存"，对应 B3-下笔记第 2 节。
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
        // 同一条 ORDER_CLOSED 被重复投递：记录已是 RELEASED，CAS 影响 0 行，库存不再加
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

        assertThat(stock(101)).containsExactly(8, 0);   // 8 件可售，2 件已卖出（不再出现在 reserved）
        assertThat(stock(102)).containsExactly(7, 0);
        assertThat(statuses(orderNo)).containsOnly(20);
    }

    /** 终态互斥：已确认（钱收了、货卖了）的预占，迟到的释放消息不能把库存再还回去 */
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
     * 并发：10 个线程同时释放、10 个线程同时确认同一个订单（模拟消息重复 + 上游出错同时发了两种结局）。
     * 每条预占记录只有一个线程 CAS 成功，库存只改一次，且两个 SKU 结局一致或各自唯一 —— 不会"既还了又扣了"。
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
        assertThat(changed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);   // 两条记录，各被处理一次
        for (long sku : new long[]{101, 102}) {
            int[] s = stock(sku);
            assertThat(s[1]).isZero();                                    // 预占全部了结
            assertThat(s[0]).isIn(10, sku == 101 ? 8 : 7);                // 要么还回去，要么卖出去，不会多还
        }
    }
}
