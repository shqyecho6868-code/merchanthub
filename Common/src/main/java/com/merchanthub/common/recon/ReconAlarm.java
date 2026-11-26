package com.merchanthub.common.recon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Called when reconciliation / compensation finds a problem it can't fix automatically: records a row in recon_exception (one table per service DB) + logs an ERROR alert (E3).
 * <p>
 * Why write to the DB rather than just log: logs roll over and get lost, and you can't count "how many are still open"; the exception table is the to-do list for manual handling,
 * and handle_status is set to 1 once resolved. In production, monitoring alerts on this table / this log keyword.
 * <p>
 * Idempotent via a (type, biz_key) unique key + INSERT IGNORE: reconciliation sees the same problem every round, but it's recorded and alerted only once, no flooding.
 * Uses JdbcTemplate rather than MyBatis: this is an infrastructure table that doesn't need the tenant plugin's condition (tenant_id is written explicitly).
 */
@Slf4j
public class ReconAlarm {

    // Alarm types (type column)
    /** Payment succeeded but the order is closed: money taken, goods not shipped, refund pending (refunds are out of scope) */
    public static final String PAID_AFTER_CLOSED = "PAID_AFTER_CLOSED";
    /** Same order paid successfully by two payments: the extra one needs a refund */
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    /** Payment amount / merchant doesn't match the order */
    public static final String PAY_MISMATCH = "PAY_MISMATCH";
    /** Payment-succeeded event republished multiple times, still not handled on the order side */
    public static final String PAY_RESEND_EXHAUSTED = "PAY_RESEND_EXHAUSTED";
    /** Local outbox message delivery failures reached the limit */
    public static final String OUTBOX_FAILED = "OUTBOX_FAILED";
    /** Message retries exhausted, moved to the dead-letter queue */
    public static final String DEAD_LETTER = "DEAD_LETTER";

    private final JdbcTemplate jdbc;

    public ReconAlarm(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if newly recorded (first time seen); false if already recorded */
    public boolean raise(long tenantId, String type, String bizKey, String detail) {
        int n = jdbc.update("INSERT IGNORE INTO recon_exception (tenant_id, type, biz_key, detail, handle_status) "
                + "VALUES (?, ?, ?, ?, 0)", tenantId, type, bizKey, truncate(detail));
        if (n == 1) {
            log.error("【对账告警】type={} bizKey={} tenantId={} detail={}", type, bizKey, tenantId, detail);
            return true;
        }
        return false;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
