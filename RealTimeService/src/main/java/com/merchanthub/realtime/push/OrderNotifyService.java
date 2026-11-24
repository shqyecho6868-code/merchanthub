package com.merchanthub.realtime.push;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单事件 → 要推的消息（E1，"商家新订单通知"演示消费者从 order 迁来后的样子）。
 * <ul>
 *   <li>ORDER_CREATED → 写商家通知（merchant_order_notice）+ 推给商家：新订单提醒</li>
 *   <li>ORDER_PAID / ORDER_CLOSED → 推给买家：订单状态变化</li>
 * </ul>
 * 去重记录和通知在同一个本地事务里（同 B2 的消费端幂等）；事务提交后才分发推送（由调用方做），
 * 重复事件返回空列表 —— 不会重复写通知，也不会重复推。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotifyService {

    private final JdbcTemplate jdbc;

    /** @return 需要推送的消息；重复事件或无需推送时为空 */
    @Transactional(rollbackFor = Exception.class)
    public List<PushMessage> record(String tag, JsonNode e) {
        long eventId = e.path("eventId").asLong();
        long tenantId = e.path("tenantId").asLong();
        long orderNo = e.path("orderNo").asLong();
        try {
            jdbc.update("INSERT INTO consumed_event (tenant_id, consumer_group, event_id) VALUES (?, ?, ?)",
                    tenantId, PushTopics.ORDER_CONSUMER_GROUP, eventId);
        } catch (DuplicateKeyException dup) {
            log.info("重复的订单事件，跳过 tag={} eventId={} orderNo={}", tag, eventId, orderNo);
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<PushMessage> out = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", String.valueOf(orderNo));
        switch (tag) {
            case PushTopics.TAG_ORDER_CREATED -> {
                long buyerId = e.path("buyerId").asLong();
                long payAmount = e.path("payAmount").asLong();
                jdbc.update("INSERT INTO merchant_order_notice (tenant_id, order_no, buyer_id, pay_amount, event_id, read_status)"
                        + " VALUES (?, ?, ?, ?, ?, 0)", tenantId, orderNo, buyerId, payAmount, eventId);
                data.put("payAmount", payAmount);
                out.add(new PushMessage("evt-" + eventId + "-m", PushMessage.merchant(tenantId), tenantId, "NEW_ORDER", data, now));
            }
            case PushTopics.TAG_ORDER_PAID, PushTopics.TAG_ORDER_CLOSED -> {
                JsonNode buyer = e.path("buyerId");
                if (buyer.isMissingNode() || buyer.isNull()) {
                    // E1 之前发出的老事件没有 buyerId：不知道推给谁，跳过（状态以订单查询为准）
                    log.info("订单事件没有 buyerId，不推送 tag={} orderNo={}", tag, orderNo);
                    return List.of();
                }
                data.put("status", PushTopics.TAG_ORDER_PAID.equals(tag) ? 20 : 50);
                data.put("event", e.path("event").asText());
                out.add(new PushMessage("evt-" + eventId + "-b", PushMessage.buyer(buyer.asLong()), tenantId, "ORDER_STATUS", data, now));
            }
            default -> {
                return List.of();
            }
        }
        return out;
    }
}
