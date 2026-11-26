-- Tables of the payment service (database mh_payment). Same conventions as other services: DATETIME(3) in UTC, money as BIGINT cents.

-- An order can have several payments (multiple payment attempts), so order_no is not unique
CREATE TABLE payment (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT      NOT NULL COMMENT 'Merchant of the order (denormalized, for per-merchant bookkeeping queries)',
    pay_no           BIGINT      NOT NULL COMMENT 'Payment number (snowflake)',
    order_no         BIGINT      NOT NULL COMMENT 'Related order (not unique: one order, several payment attempts)',
    buyer_id         BIGINT      NOT NULL,
    amount           BIGINT      NOT NULL COMMENT 'Amount of this payment (cents), taken from the order pay_amount on creation and checked on callback',
    channel          VARCHAR(32) NOT NULL COMMENT 'Only MOCK in this project',
    status           TINYINT     NOT NULL DEFAULT 0 COMMENT '0 pending / 1 succeeded / 2 failed / 3 closed',
    channel_trade_no VARCHAR(64) COMMENT 'Channel transaction number, written on callback. Unique: one channel transaction can never succeed two payments',
    paid_at          DATETIME(3),
    success_attempt  VARCHAR(36) COMMENT 'Attempt ID of the callback that won the pending->succeeded CAS; the transaction check-back uses it to tell whether a half message came from the winner',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted          TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_no (pay_no),
    -- NULL while pending; MySQL unique indexes allow multiple NULLs
    UNIQUE KEY uk_channel_trade_no (channel_trade_no),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Payment';
