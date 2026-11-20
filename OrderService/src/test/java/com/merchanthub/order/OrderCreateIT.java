package com.merchanthub.order;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.model.vo.CreateOrderVO;
import com.merchanthub.order.service.OrderCreateService;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * order 侧下单流程：真实 MySQL + Redis，product-inventory 用 Mockito 替身（只测本服务，跨服务契约另测）。
 * 预占的并发与全占全不占在 product-inventory 的 IT 里测。
 */
@SpringBootTest
class OrderCreateIT extends OrderItSupport {

    @Test
    void createOrder_writesOrderItemsSnapshotsAndLog_inOneGo() {
        CreateOrderVO vo = orderCreateService.createOrder(BUYER, req(2 * 9900L + 1999, 101, 2, 102, 1));
        long orderNo = Long.parseLong(vo.getOrderNo());

        assertThat(vo.getPayAmount()).isEqualTo(21799L);
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE order_no = ?", orderNo);
        assertThat(order.get("status")).isEqualTo(10);
        assertThat(order.get("tenant_id")).isEqualTo(TENANT);
        assertThat(order.get("buyer_id")).isEqualTo(BUYER);
        assertThat(order.get("pay_amount")).isEqualTo(21799L);

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM order_item WHERE order_no = ? ORDER BY sku_id", orderNo);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("unit_price")).isEqualTo(9900L);
        assertThat(items.get(0).get("sku_spec")).isIn("白 / L", "L / 白"); // MySQL JSON 会重排 key 顺序
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_snapshot WHERE order_no = ?", Long.class, orderNo))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT event FROM order_status_log WHERE order_no = ? AND from_status IS NULL AND to_status = 10",
                String.class, orderNo)).isEqualTo("CREATE");

        // 预占请求带着同一个订单号，SKU 按 id 升序
        ArgumentCaptor<ReserveRequest> captor = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(client).reserve(captor.capture());
        assertThat(captor.getValue().getOrderNo()).isEqualTo(orderNo);
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getItems()).extracting(ReserveRequest.Item::getSkuId).containsExactly(101L, 102L);
    }

    @Test
    void snapshotKeepsPriceAtOrderTime() {
        CreateOrderVO vo = orderCreateService.createOrder(BUYER, req(null, 101, 1));
        catalog.get(101L).setPrice(12900L); // 下单后商家改价
        String content = jdbc.queryForObject("SELECT content FROM trade_snapshot WHERE order_no = ?",
                String.class, Long.parseLong(vo.getOrderNo()));
        assertThat(content).contains("\"price\": 9900");
    }

    @Test
    void duplicateSkuLinesAreMerged() {
        orderCreateService.createOrder(BUYER, req(null, 101, 1, 101, 2));
        ArgumentCaptor<ReserveRequest> captor = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(client).reserve(captor.capture());
        assertThat(captor.getValue().getItems()).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(3));
    }

    @Test
    void priceChanged_rejectedBeforeReserving() {
        assertThatThrownBy(() -> orderCreateService.createOrder(BUYER, req(9900L - 1, 101, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(ErrorCode.PRICE_CHANGED.getCode());
        verify(client, never()).reserve(any());
    }

    @Test
    void unknownSku_rejected() {
        assertCode(() -> orderCreateService.createOrder(BUYER, req(null, 999, 1)), ErrorCode.SKU_NOT_FOUND);
    }

    @Test
    void offShelfSku_rejected() {
        catalog.get(101L).setProductStatus(3);
        assertCode(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1)), ErrorCode.SKU_NOT_ON_SALE);
    }

    @Test
    void skusFromTwoMerchants_rejected() {
        catalog.put(201L, sku(201, 8L, 500, "{}"));
        assertCode(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1, 201, 1)), ErrorCode.ORDER_MULTI_TENANT);
    }

    @Test
    void reserveFails_noOrderWritten_errorPassedThrough() {
        when(client.reserve(any())).thenReturn(
                (BaseResponse<Void>) (BaseResponse<?>) ResultUtils.error(ErrorCode.INVENTORY_INSUFFICIENT));
        assertCode(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1)), ErrorCode.INVENTORY_INSUFFICIENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    }

    @Test
    void reserveTimeout_degradesToFriendlyError() {
        Request feignReq = Request.create(Request.HttpMethod.POST, "/internal/inventory/reserve", Map.of(),
                new byte[0], StandardCharsets.UTF_8, null);
        when(client.reserve(any())).thenThrow(new RetryableException(-1, "Read timed out",
                Request.HttpMethod.POST, (Long) null, feignReq));
        assertCode(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1)), ErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code.getCode());
    }
}
