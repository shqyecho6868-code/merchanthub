-- Payment reconciliation

-- Each succeeded payment is checked "has order processed its event": 0 unchecked / 1 consistent / 3 still unprocessed after several re-sends (alerted, handed to manual handling)
-- recon_retry: number of times the payment-success event was re-sent
ALTER TABLE payment
    ADD COLUMN recon_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 unchecked / 1 consistent / 3 re-sends exhausted, alerted' AFTER success_attempt,
    ADD COLUMN recon_retry  INT     NOT NULL DEFAULT 0 COMMENT 'Times reconciliation re-sent the payment-success event' AFTER recon_status,
    -- Reconciliation scan: WHERE status = 1 AND recon_status = 0 AND paid_at < ?
    ADD KEY idx_recon (status, recon_status, paid_at),
    -- Closing stale payments: WHERE status = 0 AND created_at < ?
    ADD KEY idx_status_created (status, created_at);

-- Same structure as order's recon_exception (written by Common's ReconAlarm)
CREATE TABLE recon_exception (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    biz_key       VARCHAR(128) NOT NULL,
    detail        VARCHAR(512),
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 awaiting manual handling / 1 handled',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (type, biz_key),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Reconciliation exceptions (awaiting manual handling)';
