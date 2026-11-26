package com.merchanthub.realtime.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Push message table (offline_message): <b>store first, then push</b>.
 * <ol>
 *   <li>Before dispatch, store a "pending" row (status 0)</li>
 *   <li>Once some node actually writes it to the receiver's connection -> mark "delivered" (status 1)</li>
 *   <li>Receiver offline, target node crashed mid-forward, or connection write failed -> the row stays 0, i.e. an "offline message"</li>
 *   <li>Receiver comes online and handshakes successfully -> redeliver all 0 rows in id order</li>
 * </ol>
 * Why not "store only when the push fails": during the tens of seconds node A is down, its route hasn't expired yet and messages are still forwarded to A; A is gone, so nobody can report back "I didn't deliver it".
 * Only "store first, mark when delivered" guarantees these messages aren't lost. Cost: one extra DB write per push; a message may be delivered more than once (clients dedupe by msgId).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineMessageStore {

    private static final int REPLAY_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Store a pending row; the same msgId for the same receiver is stored only once (INSERT IGNORE + unique key), so MQ redelivery / multi-node duplicate saves are harmless */
    public void save(PushMessage msg) {
        jdbc.update("INSERT IGNORE INTO offline_message (tenant_id, receiver_key, msg_id, type, payload) VALUES (?, ?, ?, ?, ?)",
                msg.tenantId(), msg.receiverKey(), msg.msgId(), msg.type(), toJson(msg));
    }

    public void markDelivered(String receiverKey, String msgId) {
        jdbc.update("UPDATE offline_message SET status = 1 WHERE receiver_key = ? AND msg_id = ? AND status = 0", receiverKey, msgId);
    }

    /**
     * Redelivery on coming online: write undelivered rows to the connection in id order, marking only on successful write (failed writes are retried next time online).
     *
     * @return number of messages redelivered this time
     */
    public int replay(String receiverKey, Channel channel) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT msg_id, payload FROM offline_message WHERE receiver_key = ? AND status = 0 AND deleted = 0"
                        + " ORDER BY id LIMIT " + REPLAY_LIMIT, receiverKey);
        for (Map<String, Object> row : rows) {
            String msgId = (String) row.get("msg_id");
            channel.writeAndFlush(new TextWebSocketFrame(String.valueOf(row.get("payload")))).addListener(f -> {
                if (f.isSuccess()) {
                    markDelivered(receiverKey, msgId);
                }
            });
        }
        if (!rows.isEmpty()) {
            log.info("【离线补发】{} 上线，补发 {} 条", receiverKey, rows.size());
        }
        return rows.size();
    }

    public String toJson(PushMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
