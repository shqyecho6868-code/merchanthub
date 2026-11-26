-- Tables of the product-inventory service (database mh_product).
-- Conventions: all time columns are DATETIME(3) in UTC (the connection session time zone is pinned to UTC, see application.yml);
--      created_at / updated_at are maintained by database defaults, never written by the app; deleted = soft delete.
-- Baseline migration: never edit it once applied; schema changes go into a new V file.

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL COMMENT 'Merchant',
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    main_image  VARCHAR(512),
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 draft / 1 under review / 2 listed / 3 delisted / 4 review rejected',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Product (SPU)';

CREATE TABLE IF NOT EXISTS sku (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    sku_code   VARCHAR(64) NOT NULL COMMENT 'Merchant-defined code, unique within a merchant',
    spec_json  JSON        NOT NULL COMMENT 'Spec, e.g. {"color":"white","size":"L"}',
    price      BIGINT      NOT NULL COMMENT 'Selling price (cents)',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '0 off sale / 1 on sale',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_sku_code (tenant_id, sku_code),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SKU (variant)';

-- Inventory lives in its own table: write-heavy hot rows are kept apart from the read-mostly SKU description, so they don't contend for the same row lock
CREATE TABLE IF NOT EXISTS inventory (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    -- Deliberately no UNSIGNED / CHECK(available >= 0): overselling is prevented by the conditional update,
    -- and the oversell experiment needs to be able to observe negative values. A CHECK can be added later as a second line of defense
    available  INT         NOT NULL DEFAULT 0 COMMENT 'Sellable quantity',
    reserved   INT         NOT NULL DEFAULT 0 COMMENT 'Reserved quantity (ordered, not yet paid)',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- The reservation SQL locates rows by sku_id via this unique index, locking only that row
    UNIQUE KEY uk_sku (sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Inventory';

-- Reservation records: how much of each SKU an order holds; release / confirm-deduct go by these rows, which keeps them idempotent
CREATE TABLE IF NOT EXISTS inventory_reservation (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    order_no   BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL COMMENT 'Reserved quantity',
    status     TINYINT     NOT NULL DEFAULT 10 COMMENT '10 RESERVED / 20 CONFIRMED / 30 RELEASED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- An order reserves a given SKU at most once: retries / concurrent duplicates hit this key and the whole reservation transaction rolls back
    UNIQUE KEY uk_order_sku (order_no, sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Inventory reservations';
