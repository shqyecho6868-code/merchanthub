-- payment 服务的表（库 mh_payment）。设计见 docs/data-model.md 3.6。约定同其他服务：DATETIME(3) 存 UTC，金额 BIGINT 分。

-- 一个订单可以有多条支付单（多次支付尝试），所以 order_no 不唯一
CREATE TABLE payment (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT      NOT NULL COMMENT '订单所属商家（冗余，按商家查账用）',
    pay_no           BIGINT      NOT NULL COMMENT '支付单号（雪花）',
    order_no         BIGINT      NOT NULL COMMENT '关联订单（不唯一：一单多次支付尝试）',
    buyer_id         BIGINT      NOT NULL,
    amount           BIGINT      NOT NULL COMMENT '本次支付金额（分），建单时从订单 pay_amount 取，回调时校验',
    channel          VARCHAR(32) NOT NULL COMMENT '本项目只有 MOCK',
    status           TINYINT     NOT NULL DEFAULT 0 COMMENT '0 待支付 / 1 成功 / 2 失败 / 3 已关闭',
    channel_trade_no VARCHAR(64) COMMENT '渠道流水号，回调时写入。唯一键：同一笔渠道流水不可能成功两张支付单',
    paid_at          DATETIME(3),
    success_attempt  VARCHAR(36) COMMENT '赢得 待支付→成功 CAS 的那次回调的尝试 ID；事务回查靠它分辨半消息是不是赢家发的（B3 踩坑 P19）',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted          TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_no (pay_no),
    -- 待支付时为 NULL；MySQL 唯一索引允许多个 NULL
    UNIQUE KEY uk_channel_trade_no (channel_trade_no),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付单';
