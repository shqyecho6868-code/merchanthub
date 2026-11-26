-- Transactional outbox: written in the same local transaction as the order, delivered by OutboxRelay's scan
CREATE TABLE order_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL COMMENT 'Unique event ID (snowflake); unchanged on resend, consumers dedupe on it',
    biz_key       VARCHAR(64)  NOT NULL COMMENT 'Business key (order_no here); also set as message keys for troubleshooting',
    topic         VARCHAR(64)  NOT NULL,
    tag           VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL COMMENT 'Message body (event JSON)',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 pending / 1 sent / 2 failed (retry limit exceeded, needs manual handling)',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL COMMENT 'Earliest time it may be sent (again); also the lease deadline while sending',
    last_error    VARCHAR(512) COMMENT 'Reason of the last send failure',
    sent_at       DATETIME(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    -- Scan condition: WHERE status = 0 AND next_retry_at <= now
    KEY idx_status_next_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Order event outbox';
