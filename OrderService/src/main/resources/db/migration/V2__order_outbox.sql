-- B2 方案二：本地消息表（Outbox）。和订单在同一个本地事务里写入，由 OutboxRelay 扫描投递。字段设计见 B2 笔记 Q3
CREATE TABLE order_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL COMMENT '事件唯一 ID（雪花），重发不变，消费端按它幂等',
    biz_key       VARCHAR(64)  NOT NULL COMMENT '业务键，这里是 order_no；放进消息 keys 方便排查',
    topic         VARCHAR(64)  NOT NULL,
    tag           VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL COMMENT '消息体（事件 JSON）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待发送 / 1 已发送 / 2 发送失败（超过重试上限，需人工处理）',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL COMMENT '最早什么时候可以（再）发送；也用作发送中的租约截止时间',
    last_error    VARCHAR(512) COMMENT '最近一次发送失败的原因',
    sent_at       DATETIME(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    -- 扫描条件：WHERE status = 0 AND next_retry_at <= now
    KEY idx_status_next_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单事件本地消息表';
