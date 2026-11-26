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
 * Order event -> messages to push (shows what the "merchant new-order notice" demo consumer looks like after moving here from order).
 * <ul>
 *   <li>ORDER_CREATED -> write a merchant notice (merchant_order_notice) + push to the merchant: new-order alert</li>
 *   <li>ORDER_PAID / ORDER_CLOSED -> push to the buyer: order status change</li>
 * </ul>
 * The dedup record and the notice are in the same local transaction (same consumer idempotency as order); pushes are dispatched only after commit (by the caller),
 * and duplicate events return an empty list -- no duplicate notices, no duplicate pushes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotifyService {

    private final JdbcTemplate jdbc;

    /** @return messages to push; empty for duplicate events or when nothing needs pushing */
    @Transactional(rollbackFor = Exception.class)
    public List<PushMessage> record(String tag, JsonNode e) {
        long eventId = e.path("eventId").asLong();
        long tenantId = e.path("tenantId").asLong();
        long orderNo = e.path("orderNo").asLong();
        try {
            jdbc.update("INSERT INTO consumed_event (tenant_id, consumer_group, event_id) VALUES (?, ?, ?)",
                    tenantId, PushTopics.ORDER_CONSUMER_GROUP, eventId);
        } catch (DuplicateKeyException dup) {
            log.info("Duplicate order event, skipping tag={} eventId={} orderNo={}", tag, eventId, orderNo);
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
                    // Old events sent before buyerId was added have none: we don't know whom to push to, so skip (order queries are the source of truth for status)
                    log.info("Order event has no buyerId, not pushing tag={} orderNo={}", tag, orderNo);
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
