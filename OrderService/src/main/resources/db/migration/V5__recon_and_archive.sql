-- Reconciliation exception table + outbox archive + timeout-scan index; merchant notices move to realtime

-- Problems that reconciliation / compensation cannot fix automatically are recorded here (written by Common's ReconAlarm): the to-do list for manual handling.
-- (type, biz_key) is unique: every reconciliation round sees the same problem again; record and alert only once
CREATE TABLE recon_exception (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT 'Merchant involved; 0 for cross-merchant system issues',
    type          VARCHAR(32)  NOT NULL COMMENT 'PAID_AFTER_CLOSED / DUPLICATE_PAYMENT / PAY_MISMATCH / OUTBOX_FAILED / DEAD_LETTER ...',
    biz_key       VARCHAR(128) NOT NULL COMMENT 'Business key to locate the issue, e.g. payNo, outbox id, consumerGroup:msgId',
    detail        VARCHAR(512),
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 awaiting manual handling / 1 handled',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (type, biz_key),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Reconciliation exceptions (awaiting manual handling)';

-- Sent outbox rows past the retention period move to the archive: the main table keeps only the few in-flight rows, so the scan index stays small.
-- Same structure as the main table (without the scan index); only used afterwards to check "was this event sent back then"
CREATE TABLE order_outbox_archive LIKE order_outbox;
ALTER TABLE order_outbox_archive DROP INDEX idx_status_next_retry, ADD KEY idx_biz_key (biz_key);

-- Timeout order scan: WHERE status = 10 AND expire_at < ?
ALTER TABLE orders ADD KEY idx_status_expire (status, expire_at);

-- Sent-row archive scan: WHERE status = 1 AND sent_at < ?
ALTER TABLE order_outbox ADD KEY idx_status_sent (status, sent_at);

-- New pay_anomaly handle_status value: 2 = flagged for refund and alerted (refunds are out of scope; handled manually / by a future refund flow)
ALTER TABLE pay_anomaly MODIFY handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending / 1 handled / 2 flagged for refund and alerted';

-- Merchant new-order notices moved with the demo consumer to the realtime service (database mh_realtime); order no longer writes this table
DROP TABLE merchant_order_notice;
