-- E3：支付对账

-- 支付成功的单据逐张核对"order 处理过它的事件没有"：0 未核对 / 1 已一致 / 3 补发多次仍未处理（已告警，转人工）
-- recon_retry：补发支付成功事件的次数
ALTER TABLE payment
    ADD COLUMN recon_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 未核对 / 1 已一致 / 3 补发耗尽已告警' AFTER success_attempt,
    ADD COLUMN recon_retry  INT     NOT NULL DEFAULT 0 COMMENT '对账补发支付成功事件的次数' AFTER recon_status,
    -- 对账扫描：WHERE status = 1 AND recon_status = 0 AND paid_at < ?
    ADD KEY idx_recon (status, recon_status, paid_at),
    -- 关闭遗留支付单：WHERE status = 0 AND created_at < ?
    ADD KEY idx_status_created (status, created_at);

-- 结构同 order 的 recon_exception（Common 的 ReconAlarm 写入）
CREATE TABLE recon_exception (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    biz_key       VARCHAR(128) NOT NULL,
    detail        VARCHAR(512),
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待人工处理 / 1 已处理',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (type, biz_key),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账异常（待人工处理）';
