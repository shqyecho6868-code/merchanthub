package com.merchanthub.product.inventory;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 上验证预占的四个性质：全占或全不占、幂等、租户隔离、并发不死锁。
 */
@SpringBootTest
class InventoryReservationIT extends IntegrationTestBase {

    private static final long TENANT = 7L;
    private static final AtomicLong ORDER_NO = new AtomicLong(1_000_000);

    @Autowired
    private InventoryReservationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE inventory");
        jdbc.execute("TRUNCATE TABLE inventory_reservation");
    }

    private void stock(long skuId, int available) {
        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (?, ?, ?, 0)",
                TENANT, skuId, available);
    }

    private int available(long skuId) {
        return jdbc.queryForObject("SELECT available FROM inventory WHERE sku_id = ?", Integer.class, skuId);
    }

    private int reserved(long skuId) {
        return jdbc.queryForObject("SELECT reserved FROM inventory WHERE sku_id = ?", Integer.class, skuId);
    }

    private long records(long orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE order_no = ?", Long.class, orderNo);
    }

    private static ReserveRequest req(long orderNo, long tenant, long... skuQty) {
        List<ReserveRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            items.add(new ReserveRequest.Item(skuQty[i], (int) skuQty[i + 1]));
        }
        return new ReserveRequest(orderNo, tenant, items);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code.getCode());
    }

    @Test
    void multiSku_allSufficient_allReserved() {
        stock(101, 10);
        stock(102, 10);
        long orderNo = ORDER_NO.incrementAndGet();
        service.reserve(req(orderNo, TENANT, 101, 2, 102, 5));

        assertThat(available(101)).isEqualTo(8);
        assertThat(reserved(101)).isEqualTo(2);
        assertThat(available(102)).isEqualTo(5);
        assertThat(reserved(102)).isEqualTo(5);
        assertThat(records(orderNo)).isEqualTo(2);
    }

    /** 全占或全不占：102 不够 → 101 已经扣的也要回滚，记录一条都不留（B1 code review 那个 bug 的回归测试） */
    @Test
    void multiSku_oneInsufficient_nothingReserved() {
        stock(101, 10);
        stock(102, 3);
        long orderNo = ORDER_NO.incrementAndGet();

        assertCode(() -> service.reserve(req(orderNo, TENANT, 101, 2, 102, 5)), ErrorCode.INVENTORY_INSUFFICIENT);

        assertThat(available(101)).isEqualTo(10);
        assertThat(reserved(101)).isZero();
        assertThat(available(102)).isEqualTo(3);
        assertThat(reserved(102)).isZero();
        assertThat(records(orderNo)).isZero();
    }

    @Test
    void retrySameRequest_reservedOnlyOnce() {
        stock(101, 10);
        long orderNo = ORDER_NO.incrementAndGet();
        service.reserve(req(orderNo, TENANT, 101, 2));
        service.reserve(req(orderNo, TENANT, 101, 2)); // Feign 超时后重试

        assertThat(available(101)).isEqualTo(8);
        assertThat(reserved(101)).isEqualTo(2);
    }

    @Test
    void sameOrderNoDifferentContent_conflict() {
        stock(101, 10);
        stock(103, 10);
        long orderNo = ORDER_NO.incrementAndGet();
        service.reserve(req(orderNo, TENANT, 101, 2));

        assertCode(() -> service.reserve(req(orderNo, TENANT, 103, 1)), ErrorCode.RESERVATION_CONFLICT);
        assertThat(available(103)).isEqualTo(10);
    }

    /** 租户隔离：拿别的商家的 tenant_id 占不到这一行（条件更新带 tenant_id） */
    @Test
    void otherTenant_cannotReserve() {
        stock(101, 10);
        assertCode(() -> service.reserve(req(ORDER_NO.incrementAndGet(), 8L, 101, 1)), ErrorCode.INVENTORY_INSUFFICIENT);
        assertThat(available(101)).isEqualTo(10);
    }

    /** 并发重复请求（同一订单号同时到达 20 次）：唯一键兜底，只扣一次，所有请求都返回成功 */
    @Test
    void concurrentDuplicates_reservedOnlyOnce() throws Exception {
        stock(101, 10);
        stock(102, 10);
        long orderNo = ORDER_NO.incrementAndGet();
        List<Throwable> errors = runConcurrently(20, i -> () -> {
            service.reserve(req(orderNo, TENANT, 101, 2, 102, 1));
            return null;
        });

        assertThat(errors).isEmpty();
        assertThat(available(101)).isEqualTo(8);
        assertThat(available(102)).isEqualTo(9);
        assertThat(records(orderNo)).isEqualTo(2);
    }

    /**
     * 防死锁：一半订单请求顺序是 [101,102]，一半是 [102,101]，并发执行。
     * 服务内部按 skuId 排序后加锁，不会出现 MySQL 1213 Deadlock。
     * （实验：把 reserve() 里的排序去掉再跑，会看到 DeadlockLoserDataAccessException）
     */
    @Test
    void crossOrderedSkus_noDeadlock() throws Exception {
        stock(101, 1000);
        stock(102, 1000);
        List<Throwable> errors = runConcurrently(50, i -> () -> {
            long orderNo = ORDER_NO.incrementAndGet();
            if (i % 2 == 0) {
                service.reserve(req(orderNo, TENANT, 101, 1, 102, 1));
            } else {
                service.reserve(req(orderNo, TENANT, 102, 1, 101, 1));
            }
            return null;
        });

        assertThat(errors).isEmpty();
        assertThat(available(101)).isEqualTo(950);
        assertThat(available(102)).isEqualTo(950);
    }

    interface TaskFactory {
        Callable<Void> create(int index);
    }

    /** 所有线程在同一个起跑线（CountDownLatch）同时开始，尽量制造真正的并发 */
    static List<Throwable> runConcurrently(int threads, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Void> task = factory.create(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Throwable> errors = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            return errors;
        } finally {
            pool.shutdownNow();
        }
    }
}
