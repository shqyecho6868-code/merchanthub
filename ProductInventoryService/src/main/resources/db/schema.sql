-- product-inventory 服务的表（库 mh_product）。设计见 docs/data-model.md。
-- 约定：时间列一律 DATETIME(3) 存 UTC（连接会话时区固定为 UTC，见 application.yml）；
--      created_at / updated_at 由数据库默认值维护，应用不写；deleted 为逻辑删除。
-- 用 IF NOT EXISTS，服务每次启动执行都是幂等的（spring.sql.init）。

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL COMMENT '商家',
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    main_image  VARCHAR(512),
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 草稿 / 1 审核中 / 2 已上架 / 3 已下架 / 4 审核驳回',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品（SPU）';

CREATE TABLE IF NOT EXISTS sku (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    sku_code   VARCHAR(64) NOT NULL COMMENT '商家自定义编码，同一商家内唯一',
    spec_json  JSON        NOT NULL COMMENT '规格，如 {"颜色":"白","尺码":"L"}',
    price      BIGINT      NOT NULL COMMENT '售价（分）',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '0 停售 / 1 在售',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_sku_code (tenant_id, sku_code),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SKU（规格）';

-- 库存单独成表：写多的热点行，和读多写少的 SKU 描述分开，避免抢同一行锁（D15）
CREATE TABLE IF NOT EXISTS inventory (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    -- 故意不用 UNSIGNED / CHECK(available >= 0)：防超卖由条件更新负责，B1 破坏性实验要能观察到负数。
    -- 实验后可以考虑加 CHECK 作为第二道防线（见 B1 笔记）
    available  INT         NOT NULL DEFAULT 0 COMMENT '可售数量',
    reserved   INT         NOT NULL DEFAULT 0 COMMENT '预占数量（已下单未支付）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 预占 SQL 按 sku_id 定位，走这个唯一索引，只锁这一行
    UNIQUE KEY uk_sku (sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存';

-- 预占记录：按订单号记录每个 SKU 占了多少，B3 释放 / 确认扣减以它为准，保证幂等
CREATE TABLE IF NOT EXISTS inventory_reservation (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    order_no   BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL COMMENT '预占数量',
    status     TINYINT     NOT NULL DEFAULT 10 COMMENT '10 RESERVED / 20 CONFIRMED / 30 RELEASED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一订单对同一 SKU 最多占一次：重试 / 并发重复请求撞这个键，整个预占事务回滚
    UNIQUE KEY uk_order_sku (order_no, sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存预占记录';
