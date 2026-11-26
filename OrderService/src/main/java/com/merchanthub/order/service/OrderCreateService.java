package com.merchanthub.order.service;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.exception.ThrowUtils;
import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.RemoteResults;
import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.client.dto.SkuQueryRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.model.enums.OrderStatus;
import com.merchanthub.order.model.vo.CreateOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Order placement orchestration: validate SKUs -> price -> generate order number -> reserve inventory (remote, tx A) -> persist order and emit event (local, tx B).
 * <p>
 * This method is <b>intentionally not @Transactional</b>: it makes two remote calls, and a transaction would hold a DB connection while waiting on the network;
 * besides, a local transaction can't roll back a remote reservation. Only "persist order + event" truly needs atomicity, and that is delegated to {@link OrderPlacer}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final ProductInventoryClient productInventoryClient;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderPlacer orderPlacer;
    private final Clock clock;

    /** Payment deadline, Duration format (15m / 30s); can be shortened for experiments */
    @Value("${merchanthub.order.pay-timeout:15m}")
    private Duration payTimeout;

    /** For 🧑 experiments only: pause after inventory reservation and before persisting the order, to make "payment callback arrives before the order" happen (B3-下 experiment ①). Keep 0 in production */
    @Value("${merchanthub.order.debug.pause-before-persist-ms:0}")
    private long pauseBeforePersistMs;

    public CreateOrderVO createOrder(long buyerId, CreateOrderRequest request) {
        // 1. Merge quantities for repeated SKUs; TreeMap fixes the later iteration order (ascending skuId)
        Map<Long, Integer> quantities = request.getItems().stream().collect(Collectors.toMap(
                CreateOrderRequest.Item::getSkuId, CreateOrderRequest.Item::getQuantity, Integer::sum, TreeMap::new));

        // 2. Fetch SKUs (prices here are authoritative)
        List<SkuSnapshot> found = RemoteResults.call(ProductInventoryClient.RES_QUERY_SKUS,
                () -> productInventoryClient.querySkus(new SkuQueryRequest(new ArrayList<>(quantities.keySet()))));
        Map<Long, SkuSnapshot> skus = found.stream().collect(Collectors.toMap(SkuSnapshot::getSkuId, Function.identity()));

        // 3. Validate: all exist, all sellable, all from the same merchant (one merchant per order)
        ThrowUtils.throwIf(!skus.keySet().containsAll(quantities.keySet()), ErrorCode.SKU_NOT_FOUND);
        ThrowUtils.throwIf(skus.values().stream().anyMatch(s -> !s.isSellable()), ErrorCode.SKU_NOT_ON_SALE);
        List<Long> tenants = skus.values().stream().map(SkuSnapshot::getTenantId).distinct().toList();
        ThrowUtils.throwIf(tenants.size() != 1, ErrorCode.ORDER_MULTI_TENANT);
        long tenantId = tenants.get(0);
        // C2: entry rate limiting uses the tenantId in the request; verify it matches the SKUs' real owner (prevents using another merchant's ID to dodge your own quota)
        ThrowUtils.throwIf(request.getTenantId() != null && request.getTenantId() != tenantId, ErrorCode.SKU_TENANT_MISMATCH);

        OrderPricing.Result pricing = OrderPricing.price(quantities, skus);
        if (request.getExpectedPayAmount() != null && request.getExpectedPayAmount() != pricing.payAmount()) {
            // Price changed: don't silently order at the new price; let the buyer reconfirm (B1 notes 5.1)
            throw new BusinessException(ErrorCode.PRICE_CHANGED);
        }

        // 4. The order number must be generated before reserving: it's the reservation's idempotency key (B1 notes 5.2)
        long orderNo = idGenerator.nextId();

        // 5. Reserve inventory (product-inventory side tx A: reserve all or nothing)
        List<ReserveRequest.Item> reserveItems = quantities.entrySet().stream()
                .map(e -> new ReserveRequest.Item(e.getKey(), e.getValue())).toList();
        RemoteResults.call(ProductInventoryClient.RES_RESERVE,
                () -> productInventoryClient.reserve(new ReserveRequest(orderNo, tenantId, reserveItems)));

        // 6. Persist order + "order created" event (succeed or fail together; see OrderPlacer's two implementations).
        // Known gap: if this fails, the inventory reserved in step 5 isn't released automatically -> B3 compensates via reservation records (B1 notes section 2)
        if (pauseBeforePersistMs > 0) {
            log.warn("[experiment] Inventory reserved, pausing {} ms before persisting orderNo={} tenantId={} payAmount={}",
                    pauseBeforePersistMs, orderNo, tenantId, pricing.payAmount());
            try {
                Thread.sleep(pauseBeforePersistMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expireAt = now.plus(payTimeout);
        try {
            orderPlacer.place(new NewOrder(orderNo, tenantId, buyerId, pricing, now, expireAt));
        } catch (RuntimeException e) {
            log.error("Failed to persist order, inventory reserved and pending compensation orderNo={} tenantId={}", orderNo, tenantId, e);
            throw e;
        }
        log.info("Order placed orderNo={} tenantId={} buyerId={} payAmount={}", orderNo, tenantId, buyerId, pricing.payAmount());
        return new CreateOrderVO(String.valueOf(orderNo), OrderStatus.PENDING_PAYMENT.getCode(),
                pricing.payAmount(), expireAt);
    }
}
