-- Tables of the realtime service (database mh_realtime). Same conventions as other services: DATETIME(3) in UTC, created_at / updated_at maintained by the database.

-- Consumption dedup (same as order's consumed_event): when an order event is redelivered, only one merchant notice is written and only one round of pushes is sent
CREATE TABLE consumed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_id       BIGINT      NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_event (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Consumed message dedup records';

-- Merchant new-order notices (moved here from order): the merchant's "notification center"; a push is only a reminder, the notice itself is persisted here
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

-- Offline messages: if the receiver is offline at push time (no node in the routing table) or the connection just dropped,
-- store the message here and re-send it in id order after the next successful handshake
CREATE TABLE offline_message (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT      NOT NULL COMMENT 'Merchant involved (buyer order-status messages also record the order''s merchant)',
    receiver_key VARCHAR(64) NOT NULL COMMENT 'Receiver: merchant:{tenantId} / buyer:{userId}',
    msg_id       VARCHAR(64) NOT NULL COMMENT 'Push message ID; clients dedupe on it',
    type         VARCHAR(32) NOT NULL,
    payload      TEXT        NOT NULL COMMENT 'Full message pushed to the client. TEXT, not JSON: a JSON column reorders fields and adds spaces, so re-sent messages would differ from real-time ones',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0 pending re-send / 1 delivered (written to the connection)',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- A message is stored at most once per receiver: several nodes deciding "offline" at once, or MQ redelivery, can't create duplicates
    UNIQUE KEY uk_receiver_msg (receiver_key, msg_id),
    -- Re-send on reconnect: WHERE receiver_key = ? AND status = 0 ORDER BY id
    KEY idx_receiver_status (receiver_key, status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Offline push messages';
