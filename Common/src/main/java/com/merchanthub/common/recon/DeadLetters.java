package com.merchanthub.common.recon;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * Dead-letter monitoring (E3): called when a consumer returns RECONSUME_LATER. If this was the last retry, the Broker will next move the message
 * into %DLQ%{consumer group} and never redeliver it automatically, so this is the moment to alert and record it in the exception table.
 * <p>
 * Why check in the consumer instead of subscribing to the DLQ topic: RocketMQ creates DLQ topics as write-only (perm=2),
 * so they can't be consumed without changing permissions, and the DLQ topic doesn't even exist until the first dead letter. The consumer knows best that "this is the last attempt", so checking there is the most direct.
 * Trade-off: it only catches the "retries exhausted" path into the DLQ (which is the only path anyway); if writing the alert to the DB fails (e.g. the DB is exactly what's down), only the log remains.
 */
@Slf4j
public final class DeadLetters {

    /** RocketMQ retries up to 16 times by default (getMaxReconsumeTimes returns -1 for the default) */
    static final int DEFAULT_MAX_RECONSUME = 16;

    private DeadLetters() {
    }

    /** Whether this failure sends the message to the DLQ: the retry count has reached the limit */
    public static boolean isLastAttempt(MessageExt m, int maxReconsumeTimes) {
        int max = maxReconsumeTimes < 0 ? DEFAULT_MAX_RECONSUME : maxReconsumeTimes;
        return m.getReconsumeTimes() >= max;
    }

    /**
     * Call on consume failure: on the last attempt, alert + record in the exception table; otherwise do nothing.
     *
     * @param alarm may be null (services without a data source); then only log
     * @return true if this message is about to go to the DLQ
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
