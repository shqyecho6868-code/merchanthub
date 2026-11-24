-- E3：对账异常 / 死信告警记录（结构同 order、payment，Common 的 ReconAlarm 写入）
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
