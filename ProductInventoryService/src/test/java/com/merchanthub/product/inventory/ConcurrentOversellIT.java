package com.merchanthub.product.inventory;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import com.merchanthub.product.ProductItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 core concurrency test: 100 buyers race for a SKU with stock 10, each buying 1 unit with a distinct order number.
 * <p>
 * Expected: exactly 10 orders succeed, 90 get "insufficient stock"; available = 0, reserved = 10, 10 reservation records.
 * What prevents overselling is the conditional update in InventoryMapper.tryReserve {@code WHERE ... AND available >= #{qty}}:
 * UPDATEs on the same row are serialized by InnoDB row locks, so each request evaluates the latest available committed by the previous one.
 * <p>
 * Destructive experiment (B1, 2026-11-18): remove {@code AND available >= #{qty}} and rerun this test →
 * 100 orders succeed, 0 insufficient, available = -90, reserved = 100, 100 reservation records — oversold by 90 units.
 * Conclusions:
 * 1. Oversell prevention relies on the WHERE condition: it makes "check whether there's enough" and "deduct" happen atomically in one statement;
 * 2. Even without the condition, available is exactly -90 rather than something messier: row locks still serialize the 100 deductions and none is lost (no lost updates).
 *    Row locks make the deduction "accurate", the condition makes it "stop at 0" — both are required;
 * 3. Counter-example: written as "SELECT available first, check in Java, then UPDATE SET available = new value",
 *    two requests may read the same old value, causing both overselling and lost updates — which is why the check must live in the UPDATE's WHERE.
 * Restored, this test serves as a regression test: if anyone removes the condition, CI goes red.
 */
@SpringBootTest
class ConcurrentOversellIT extends ProductItSupport {

    private static final long TENANT = 7L;
    private static final long SKU = 101L;
    private static final int STOCK = 10;
    private static final int BUYERS = 100;

    @Autowired
    private InventoryReservationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE inventory");
        jdbc.execute("TRUNCATE TABLE inventory_reservation");
        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (?, ?, ?, 0)",
                TENANT, SKU, STOCK);
    }

    @Test
    void hundredBuyersTenStock_noOversell() throws Exception {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        List<Throwable> otherErrors = InventoryReservationIT.runConcurrently(BUYERS, i -> () -> {
            try {
                service.reserve(new ReserveRequest(10_000L + i, TENANT, List.of(new ReserveRequest.Item(SKU, 1))));
                success.incrementAndGet();
            } catch (BusinessException e) {
                if (e.getCode() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
                    insufficient.incrementAndGet();
                } else {
                    throw e;
                }
            }
            return null;
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT available, reserved FROM inventory WHERE sku_id = ?", SKU);
        long records = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Long.class);

        // Printed for humans: watch this line during the 🧑 hands-on run
        System.out.printf("%n===== 并发下单结果：成功 %d 单，库存不足 %d 单，其他异常 %d 个；available=%s，reserved=%s，预占记录 %d 条 =====%n%n",
                success.get(), insufficient.get(), otherErrors.size(), row.get("available"), row.get("reserved"), records);

        assertThat(otherErrors).isEmpty();
        assertThat(success.get()).isEqualTo(STOCK);
        assertThat(insufficient.get()).isEqualTo(BUYERS - STOCK);
        assertThat(row.get("available")).isEqualTo(0);
        assertThat(row.get("reserved")).isEqualTo(STOCK);
        assertThat(records).isEqualTo(STOCK);
    }
}
