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
 * B1 核心并发测试：100 个买家同时抢库存为 10 的 SKU，每人买 1 件，各自不同的订单号。
 * <p>
 * 期望：恰好 10 单成功、90 单“库存不足”；available = 0，reserved = 10，预占记录 10 条。
 * 防住超卖的是 InventoryMapper.tryReserve 里的条件更新 {@code WHERE ... AND available >= #{qty}}：
 * 同一行上的 UPDATE 由 InnoDB 行锁串行化，每个请求判断的都是前一个提交后的最新 available。
 * <p>
 * 破坏性实验（B1，2026-11-18）：去掉 {@code AND available >= #{qty}} 后再跑本测试 →
 * 成功 100 单、库存不足 0 单，available = -90，reserved = 100，预占记录 100 条，超卖 90 件。
 * 结论：
 * 1. 防超卖靠的是 WHERE 里的条件：它让“判断够不够”和“扣减”在同一条语句里原子完成；
 * 2. 即使去掉条件，available 也恰好是 -90 而不是更乱的数：行锁仍让 100 次扣减串行执行，一次都没丢（没有丢失更新）。
 *    行锁保证“扣得准”，条件保证“扣到 0 就停”，两者缺一不可；
 * 3. 反面对照：如果写成“先 SELECT available，在 Java 里判断，再 UPDATE SET available = 新值”，
 *    两个请求可能读到同一个旧值，既会超卖也会丢失更新——这就是为什么判断必须放进 UPDATE 的 WHERE 里。
 * 恢复后本测试即为回归测试：任何人删掉这个条件，CI 会变红。
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

        // 打印给人看：🧑 亲手跑时观察这一行
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
