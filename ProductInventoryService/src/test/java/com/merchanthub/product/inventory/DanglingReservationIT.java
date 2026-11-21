package com.merchanthub.product.inventory;

import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.product.inventory.client.OrderClient;
import com.merchanthub.product.inventory.job.DanglingReservationScanner;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import com.merchanthub.testsupport.IntegrationTestBase;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 悬空预占补偿：只释放"足够老 + order 明确说不存在"的预占。
 * 三种不能释放的情况各一个用例：订单存在、预占太新、查询订单失败。
 */
@SpringBootTest(properties = "merchanthub.inventory.dangling-scan.older-than=30m")
class DanglingReservationIT extends IntegrationTestBase {

    private static final long TENANT = 7L;

    @MockitoBean
    private OrderClient orderClient;
    @Autowired
    private InventoryReservationService reserveService;
    @Autowired
    private DanglingReservationScanner scanner;
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

    @Test
    void oldAndOrderMissing_released_othersUntouched() {
        long dangling = reserve(1001, 40);   // 老 + 订单不存在 → 释放
        long alive = reserve(1002, 40);      // 老 + 订单存在（比如超时消息还没到）→ 不碰
        long fresh = reserve(1003, 0);       // 新：订单可能还在落库 → 根本不是候选
        when(orderClient.existing(any())).thenReturn(ResultUtils.success(List.of(alive)));

        assertThat(scanner.scan()).isEqualTo(1);
        assertThat(status(dangling)).isEqualTo(30);
        assertThat(status(alive)).isEqualTo(10);
        assertThat(status(fresh)).isEqualTo(10);
        assertThat(stock()).containsEntry("available", 98).containsEntry("reserved", 2);

        // 再扫一轮：已释放的不再是候选，库存不会多还
        assertThat(scanner.scan()).isZero();
        assertThat(stock()).containsEntry("available", 98);
    }

    /** 查不到 ≠ 不存在：order 超时 / 宕机时一条都不释放，下一轮再说 */
    @Test
    void orderServiceDown_nothingReleased() {
        long dangling = reserve(2001, 40);
        when(orderClient.existing(any())).thenThrow(new FeignException.ServiceUnavailable("order down",
                Request.create(Request.HttpMethod.POST, "/internal/orders/existing", Map.of(), null,
                        StandardCharsets.UTF_8, null), null, null));

        assertThat(scanner.scan()).isZero();
        assertThat(status(dangling)).isEqualTo(10);
        assertThat(stock()).containsEntry("available", 99).containsEntry("reserved", 1);
    }

    @Test
    void noCandidates_orderServiceNotCalled() {
        reserve(3001, 0);
        assertThat(scanner.scan()).isZero();
        verify(orderClient, never()).existing(any());
    }
}
