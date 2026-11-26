-- Consumer-side idempotency + a demo downstream "new order notice for merchants" (consumer temporarily lives in order; later moved to realtime)

-- Consumption dedup table: written in the same local transaction as the business handling. Unique key conflict = this consumer group already handled this event
CREATE TABLE consumed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    consumer_group VARCHAR(64) NOT NULL COMMENT 'Each consumer group dedupes separately: notification and indexing are two downstreams, each handles the event once',
    event_id       BIGINT      NOT NULL COMMENT 'Producer-side event ID, not the RocketMQ msgId (msgId changes on resend)',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_event (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Consumed message dedup records';

-- Deliberately no unique key on order_no: idempotency is consumed_event's job; a duplicate here means idempotency failed, which tests can see directly
CREATE TABLE merchant_order_notice (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL COMMENT 'Merchant to notify',
    order_no    BIGINT      NOT NULL,
    buyer_id    BIGINT      NOT NULL,
    pay_amount  BIGINT      NOT NULL COMMENT 'Cents',
    event_id    BIGINT      NOT NULL,
    read_status TINYINT     NOT NULL DEFAULT 0 COMMENT '0 unread / 1 read',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_created (tenant_id, created_at),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'New order notice for merchants';
