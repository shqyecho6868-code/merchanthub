-- Tables of the order service (database mh_order).
-- Baseline migration: never edit it once applied; schema changes go into a new V file.
-- Conventions (same as product-inventory): DATETIME(3) stored in UTC; created_at / updated_at maintained by the database; deleted = soft delete.
-- All money columns are BIGINT, in cents.

-- Table name is orders because ORDER is an SQL keyword
CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL COMMENT 'Merchant (an order belongs to exactly one merchant)',
    order_no        BIGINT      NOT NULL COMMENT 'Business order number (snowflake)',
    buyer_id        BIGINT      NOT NULL,
    status          TINYINT     NOT NULL COMMENT '10 pending payment / 20 paid / 30 shipped / 40 completed / 50 closed',
    total_amount    BIGINT      NOT NULL COMMENT 'Goods total (cents)',
    discount_amount BIGINT      NOT NULL DEFAULT 0 COMMENT 'Total discount (cents)',
    pay_amount      BIGINT      NOT NULL COMMENT 'Amount payable = total - discount (cents)',
    expire_at       DATETIME(3) NOT NULL COMMENT 'Payment deadline',
    paid_at         DATETIME(3),
    shipped_at      DATETIME(3),
    completed_at    DATETIME(3),
    closed_at       DATETIME(3),
    close_reason    VARCHAR(64) COMMENT 'BUYER_CANCEL / PAY_TIMEOUT',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_tenant_status_created (tenant_id, status, created_at),
    KEY idx_buyer_created (buyer_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Order';

CREATE TABLE IF NOT EXISTS order_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    order_no        BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    product_name    VARCHAR(128) NOT NULL COMMENT 'Denormalized from the snapshot',
    sku_spec        VARCHAR(256) NOT NULL COMMENT 'Denormalized from the snapshot, e.g. "White / L"',
    unit_price      BIGINT       NOT NULL COMMENT 'Denormalized from the snapshot: unit price at order time (cents)',
    quantity        INT          NOT NULL,
    total_amount    BIGINT       NOT NULL COMMENT 'Unit price x quantity',
    discount_amount BIGINT       NOT NULL DEFAULT 0 COMMENT 'Discount allocated to this line',
    pay_amount      BIGINT       NOT NULL COMMENT 'Amount actually paid for this line',
    snapshot_id     BIGINT       NOT NULL COMMENT 'Points to trade_snapshot.id',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Order line item';

CREATE TABLE IF NOT EXISTS trade_snapshot (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    order_no   BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    content    JSON        NOT NULL COMMENT 'Full product info at order time: name, spec, price, description, images',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Trade snapshot';

-- Insert-only; written in the same local transaction as the order status change
CREATE TABLE IF NOT EXISTS order_status_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    order_no      BIGINT       NOT NULL,
    from_status   TINYINT COMMENT 'NULL on creation',
    to_status     TINYINT      NOT NULL,
    event         VARCHAR(32)  NOT NULL COMMENT 'CREATE / PAY_SUCCESS / BUYER_CANCEL / PAY_TIMEOUT / SHIP / CONFIRM / AUTO_CONFIRM',
    operator_type VARCHAR(16)  NOT NULL COMMENT 'BUYER / MERCHANT / SYSTEM',
    operator_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '0 for system operations',
    remark        VARCHAR(256),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_created (order_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Order status history';
