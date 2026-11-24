package com.merchanthub.common.recon;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * 死信监控（E3）：消费者返回 RECONSUME_LATER 时调用。如果这已经是最后一次重试，这条消息接下来会被 Broker
 * 转进 %DLQ%{消费组}，不会再自动投递 —— 在这一刻告警并记异常表。
 * <p>
 * 为什么在消费者里判断，而不是订阅死信 Topic：RocketMQ 自动创建的死信 Topic 权限是"只写"（perm=2），
 * 不改权限根本消费不到；而且死信 Topic 在第一条死信出现前不存在。消费者自己最清楚"这是最后一次"，判断最直接。
 * 代价：只能发现"重试耗尽"这一种进死信的路径（这也是唯一的路径）；告警写库失败（比如正是数据库挂了）时只剩日志。
 */
@Slf4j
public final class DeadLetters {

    /** RocketMQ 默认最大重试 16 次（getMaxReconsumeTimes 返回 -1 表示用默认值） */
    static final int DEFAULT_MAX_RECONSUME = 16;

    private DeadLetters() {
    }

    /** 这次失败之后是否进死信：已重试次数达到上限 */
    public static boolean isLastAttempt(MessageExt m, int maxReconsumeTimes) {
        int max = maxReconsumeTimes < 0 ? DEFAULT_MAX_RECONSUME : maxReconsumeTimes;
        return m.getReconsumeTimes() >= max;
    }

    /**
     * 消费失败时调用：最后一次就告警 + 记异常表，否则什么也不做。
     *
     * @param alarm 可为 null（没有数据源的服务），此时只打日志
     * @return true 这条消息即将进死信
     */
    public static boolean onFailure(ReconAlarm alarm, String group, MessageExt m, int maxReconsumeTimes,
                                    Long tenantId, Throwable cause) {
        if (!isLastAttempt(m, maxReconsumeTimes)) {
            return false;
        }
        String bizKey = group + ":" + m.getMsgId();
        String detail = "topic=" + m.getTopic() + " tag=" + m.getTags() + " keys=" + m.getKeys()
                + " reconsumeTimes=" + m.getReconsumeTimes() + " error=" + (cause == null ? "" : cause.toString());
        log.error("【死信】消息重试耗尽，将进入 %DLQ%{} group={} msgId={} {}", group, group, m.getMsgId(), detail);
        if (alarm != null) {
            try {
                alarm.raise(tenantId == null ? 0L : tenantId, ReconAlarm.DEAD_LETTER, bizKey, detail);
            } catch (RuntimeException e) {
                log.error("死信告警写库失败，只保留日志 bizKey={}", bizKey, e);
            }
        }
        return true;
    }
}
