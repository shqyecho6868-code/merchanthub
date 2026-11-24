package com.merchanthub.realtime.push;

import java.util.Map;

/**
 * 推给客户端的消息（原样序列化成 WebSocket 文本帧）。
 *
 * @param msgId       消息 ID（由订单事件 ID 派生，重投不变）。可能重复送达（补发 / 多节点），客户端按它去重
 * @param receiverKey 接收方：merchant:{tenantId}（商家所有在线员工）/ buyer:{userId}
 * @param type        NEW_ORDER 新订单提醒 / ORDER_STATUS 订单状态变化
 * @param data        业务字段。订单号用字符串：雪花 ID 超过 JS 的安全整数范围，前端按数字解析会丢精度
 */
public record PushMessage(String msgId, String receiverKey, long tenantId, String type, Map<String, Object> data, long ts) {

    public static String merchant(long tenantId) {
        return "merchant:" + tenantId;
    }

    public static String buyer(long userId) {
        return "buyer:" + userId;
    }
}
