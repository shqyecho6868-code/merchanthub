-- B3-下：超时关单 + 支付异常记录

-- outbox 支持"定时投递"：超时关单消息和订单同事务写入，投递时间 = 订单 expire_at（RocketMQ 5 定时消息）。
-- NULL = 普通消息，立即投递
ALTER TABLE order_outbox
    ADD COLUMN deliver_at DATETIME(3) NULL COMMENT '定时投递时间（UTC），NULL 表示立即投递' AFTER payload;

-- 支付成功但订单没能变成已支付（已关单 / 已被另一张支付单支付 / 金额商家不符）：钱收了、货不发，必须退款。
-- 本任务只记录不处理，E3 对账任务读这张表发起退款（退款流程范围外）
CREATE TABLE pay_anomaly (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    pay_no        BIGINT       NOT NULL,
    order_no      BIGINT       NOT NULL,
    amount        BIGINT       NOT NULL COMMENT '支付金额（分）',
    type          VARCHAR(32)  NOT NULL COMMENT 'PAID_AFTER_CLOSED 关单后支付 / DUPLICATE_PAYMENT 重复支付 / MISMATCH 订单不存在或金额商家不符',
    order_status  TINYINT      COMMENT '记录时订单的状态',
    handle_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 已处理（E3 对账）',
    remark        VARCHAR(256),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一张支付单最多一条异常记录（消费去重之外再兜一层）
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_handle_status (handle_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付异常（待对账）';
