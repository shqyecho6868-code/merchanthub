-- Payment-timeout close + payment anomaly records

-- Outbox supports scheduled delivery: the timeout-close message is written in the same transaction as the order,
-- delivered at the order's expire_at (RocketMQ 5 scheduled message). NULL = normal message, delivered immediately
ALTER TABLE order_outbox
    ADD COLUMN deliver_at DATETIME(3) NULL COMMENT 'Scheduled delivery time (UTC); NULL = deliver immediately' AFTER payload;

-- Payment succeeded but the order could not become paid (already closed / paid by another payment / amount or merchant mismatch):
-- money was taken but goods won't ship, so it must be refunded.
-- Only recorded here; the reconciliation job reads this table to flag refunds (the refund flow itself is out of scope)
CREATE TABLE pay_anomaly (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    pay_no        BIGINT       NOT NULL,
    order_no      BIGINT       NOT NULL,
    amount        BIGINT       NOT NULL COMMENT 'Paid amount (cents)',
    type          VARCHAR(32)  NOT NULL COMMENT 'PAID_AFTER_CLOSED paid after close / DUPLICATE_PAYMENT duplicate payment / MISMATCH order missing or amount/merchant mismatch',
    order_status  TINYINT      COMMENT 'Order status at the time of recording',
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 pending / 1 handled (by reconciliation)',
    remark        VARCHAR(256),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- At most one anomaly record per payment (an extra safety net on top of consumer dedup)
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Payment anomalies (pending reconciliation)';
