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
 * 下单编排：校验 SKU → 计价 → 生成订单号 → 预占库存（远程，事务 A）→ 落订单（本地，事务 B）。
 * <p>
 * 这个方法<b>故意不加 @Transactional</b>：中间有两次远程调用，加了事务就会在等待网络期间一直占着数据库连接；
 * 而且本地事务也回滚不了远程的预占。真正需要原子性的只有“落订单”这一步，交给 {@link OrderPersistService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final ProductInventoryClient productInventoryClient;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderPersistService orderPersistService;
    private final Clock clock;

    @Value("${merchanthub.order.pay-timeout-minutes:15}")
    private long payTimeoutMinutes;

    public CreateOrderVO createOrder(long buyerId, CreateOrderRequest request) {
        // 1. 同一个 SKU 出现多次就合并数量；TreeMap 让后面的遍历顺序固定（按 skuId 升序）
        Map<Long, Integer> quantities = request.getItems().stream().collect(Collectors.toMap(
                CreateOrderRequest.Item::getSkuId, CreateOrderRequest.Item::getQuantity, Integer::sum, TreeMap::new));

        // 2. 查 SKU（价格以这里为准）
        List<SkuSnapshot> found = RemoteResults.call("querySkus",
                () -> productInventoryClient.querySkus(new SkuQueryRequest(new ArrayList<>(quantities.keySet()))));
        Map<Long, SkuSnapshot> skus = found.stream().collect(Collectors.toMap(SkuSnapshot::getSkuId, Function.identity()));

        // 3. 校验：都存在、都能卖、属于同一个商家（一单一商家）
        ThrowUtils.throwIf(!skus.keySet().containsAll(quantities.keySet()), ErrorCode.SKU_NOT_FOUND);
        ThrowUtils.throwIf(skus.values().stream().anyMatch(s -> !s.isSellable()), ErrorCode.SKU_NOT_ON_SALE);
        List<Long> tenants = skus.values().stream().map(SkuSnapshot::getTenantId).distinct().toList();
        ThrowUtils.throwIf(tenants.size() != 1, ErrorCode.ORDER_MULTI_TENANT);
        long tenantId = tenants.get(0);

        OrderPricing.Result pricing = OrderPricing.price(quantities, skus);
        if (request.getExpectedPayAmount() != null && request.getExpectedPayAmount() != pricing.payAmount()) {
            // 价格变了：不按新价格悄悄下单，让买家重新确认（B1 笔记 5.1）
            throw new BusinessException(ErrorCode.PRICE_CHANGED);
        }

        // 4. 订单号必须在预占之前生成：它是预占的幂等键（B1 笔记 5.2）
        long orderNo = idGenerator.nextId();

        // 5. 预占库存（product-inventory 侧事务 A：全占或全不占）
        List<ReserveRequest.Item> reserveItems = quantities.entrySet().stream()
                .map(e -> new ReserveRequest.Item(e.getKey(), e.getValue())).toList();
        RemoteResults.call("reserve",
                () -> productInventoryClient.reserve(new ReserveRequest(orderNo, tenantId, reserveItems)));

        // 6. 落订单（本地事务 B）。
        // 已知缺口：如果这里失败，第 5 步占的库存不会自动释放 → B2/B3 按预占记录补偿（B1 笔记第 2 节）
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expireAt = now.plus(Duration.ofMinutes(payTimeoutMinutes));
        try {
            orderPersistService.saveNewOrder(orderNo, tenantId, buyerId, pricing, expireAt);
        } catch (RuntimeException e) {
            log.error("订单落库失败，库存已预占待补偿 orderNo={} tenantId={}", orderNo, tenantId, e);
            throw e;
        }
        log.info("下单成功 orderNo={} tenantId={} buyerId={} payAmount={}", orderNo, tenantId, buyerId, pricing.payAmount());
        return new CreateOrderVO(String.valueOf(orderNo), OrderStatus.PENDING_PAYMENT.getCode(),
                pricing.payAmount(), expireAt);
    }
}
