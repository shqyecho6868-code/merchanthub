-- order 服务的表（库 mh_order）。设计见 docs/data-model.md。
-- 约定同 product-inventory：DATETIME(3) 存 UTC；created_at / updated_at 由数据库维护；deleted 逻辑删除。
-- 金额一律 BIGINT，单位分。

-- 表名 orders：order 是 SQL 关键字
CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL COMMENT '商家（一单只属于一个商家）',
    order_no        BIGINT      NOT NULL COMMENT '业务订单号（雪花）',
    buyer_id        BIGINT      NOT NULL,
    status          TINYINT     NOT NULL COMMENT '10 待支付 / 20 已支付 / 30 已发货 / 40 已完成 / 50 已关闭',
    total_amount    BIGINT      NOT NULL COMMENT '商品总额（分）',
    discount_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '优惠总额（分）',
    pay_amount      BIGINT      NOT NULL COMMENT '应付 = 总额 - 优惠（分）',
    expire_at       DATETIME(3) NOT NULL COMMENT '支付截止时间',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';

CREATE TABLE IF NOT EXISTS order_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    order_no        BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    product_name    VARCHAR(128) NOT NULL COMMENT '快照冗余',
    sku_spec        VARCHAR(256) NOT NULL COMMENT '快照冗余，如“白 / L”',
    unit_price      BIGINT       NOT NULL COMMENT '快照冗余：下单时单价（分）',
    quantity        INT          NOT NULL,
    total_amount    BIGINT       NOT NULL COMMENT '单价 × 数量',
    discount_amount BIGINT       NOT NULL DEFAULT 0 COMMENT '分摊到本行的优惠',
    pay_amount      BIGINT       NOT NULL COMMENT '本行实付',
    snapshot_id     BIGINT       NOT NULL COMMENT '指向 trade_snapshot.id',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细';

CREATE TABLE IF NOT EXISTS trade_snapshot (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    order_no   BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    content    JSON        NOT NULL COMMENT '下单时刻完整商品信息：名称、规格、价格、描述、图片',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交易快照';

-- 只插入不更新；与订单状态变更在同一个本地事务里写
CREATE TABLE IF NOT EXISTS order_status_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    order_no      BIGINT       NOT NULL,
    from_status   TINYINT COMMENT '创建时为 NULL',
    to_status     TINYINT      NOT NULL,
    event         VARCHAR(32)  NOT NULL COMMENT 'CREATE / PAY_SUCCESS / BUYER_CANCEL / PAY_TIMEOUT / SHIP / CONFIRM / AUTO_CONFIRM',
    operator_type VARCHAR(16)  NOT NULL COMMENT 'BUYER / MERCHANT / SYSTEM',
    operator_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '系统操作为 0',
    remark        VARCHAR(256),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_created (order_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单状态流水';
