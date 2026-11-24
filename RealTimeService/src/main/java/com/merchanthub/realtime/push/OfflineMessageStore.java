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
 * 推送消息表（offline_message）：<b>先存后推</b>。
 * <ol>
 *   <li>分发前先存一行"待送达"（status 0）</li>
 *   <li>某个节点真正写进了接收方的连接 → 标记"已送达"（status 1）</li>
 *   <li>接收方不在线、转发途中目标节点宕机、写连接失败 → 这行一直是 0，就是"离线消息"</li>
 *   <li>接收方上线握手成功 → 按 id 顺序把 0 的全部补发</li>
 * </ol>
 * 为什么不是"推送失败才存"：节点 A 宕机的那几十秒里，路由还没过期，消息会被转发给 A；A 已经没了，没人能回头说"我没送到"，
 * 只有"先存、送到才标记"能保证这些消息不丢。代价：每条推送多一次写库；同一消息可能送达多次（客户端按 msgId 去重）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineMessageStore {

    private static final int REPLAY_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 存一行待送达；同一接收方同一 msgId 只存一次（INSERT IGNORE + 唯一键），MQ 重投 / 多节点重复存都没事 */
    public void save(PushMessage msg) {
        jdbc.update("INSERT IGNORE INTO offline_message (tenant_id, receiver_key, msg_id, type, payload) VALUES (?, ?, ?, ?, ?)",
                msg.tenantId(), msg.receiverKey(), msg.msgId(), msg.type(), toJson(msg));
    }

    public void markDelivered(String receiverKey, String msgId) {
        jdbc.update("UPDATE offline_message SET status = 1 WHERE receiver_key = ? AND msg_id = ? AND status = 0", receiverKey, msgId);
    }

    /**
     * 上线补发：未送达的按 id 顺序写进连接，写成功才标记（写失败下次上线再补）。
     *
     * @return 本次补发的条数
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
