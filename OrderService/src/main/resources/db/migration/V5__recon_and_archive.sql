-- E3：对账异常表 + 本地消息表归档 + 超时扫描索引；E1：商家通知迁到 realtime

-- 对账 / 补偿发现"自动修不了"的问题记在这里（Common 的 ReconAlarm 写入），是人工处理的待办清单。
-- (type, biz_key) 唯一：对账每轮都会扫到同一个问题，只记一次、只告警一次
CREATE TABLE recon_exception (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '涉及的商家；跨商家的系统问题记 0',
    type          VARCHAR(32)  NOT NULL COMMENT 'PAID_AFTER_CLOSED / DUPLICATE_PAYMENT / PAY_MISMATCH / OUTBOX_FAILED / DEAD_LETTER ...',
    biz_key       VARCHAR(128) NOT NULL COMMENT '定位问题的业务键，如 payNo、outbox id、消费组:msgId',
    detail        VARCHAR(512),
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待人工处理 / 1 已处理',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (type, biz_key),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账异常（待人工处理）';

-- 已发送超过保留期的 outbox 记录搬到归档表：主表只留"还在途"的少量数据，扫描索引始终很小。
-- 结构和主表一致（不建扫描索引），只用于事后排查"这条事件当时发没发"
CREATE TABLE order_outbox_archive LIKE order_outbox;
ALTER TABLE order_outbox_archive DROP INDEX idx_status_next_retry, ADD KEY idx_biz_key (biz_key);

-- 超时订单扫描：WHERE status = 10 AND expire_at < ?
ALTER TABLE orders ADD KEY idx_status_expire (status, expire_at);

-- 已发送记录归档扫描：WHERE status = 1 AND sent_at < ?
ALTER TABLE order_outbox ADD KEY idx_status_sent (status, sent_at);

-- pay_anomaly 处理状态补充含义：2 = 已标记待退款并告警（退款范围外，人工 / 以后的退款流程接手）
ALTER TABLE pay_anomaly MODIFY handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 已处理 / 2 已标记待退款并告警';

-- E1：商家新订单通知随演示消费者迁到 realtime 服务（库 mh_realtime），order 不再写这张表
DROP TABLE merchant_order_notice;
