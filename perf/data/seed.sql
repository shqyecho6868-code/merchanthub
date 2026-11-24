-- F1 压测数据（可重复执行）。前提：user-tenant / product-inventory 至少启动过一次（Flyway 已建表）。
--   ./perf/data/seed.sh            （= 执行本文件 + 清掉上一轮压测产生的订单 / 支付 / 预占）
--
-- 两个商家，规模故意不对称，方便观察"大商家把系统打满时小商家受不受影响"这类问题：
--   901 大商家：10 个 SKU，每个库存 1,000,000（压多久都不会因为卖光而失败，除非场景就是要测卖光）
--   902 小商家：1 个 SKU，库存 100,000
-- ID 段 9xxxx / 99xxxxx 与 B~E 组实验数据（商家 7 / 8）不重叠，互不干扰。
-- 买家 200 个：perf_buyer_0001 ~ perf_buyer_0200，密码都是 password123（与 D1 种子同一个 BCrypt 哈希）

-- ---------- user-tenant：租户与账号 ----------
USE mh_user;
INSERT INTO tenant (id, name, status) VALUES (901, '压测-大商家', 1), (902, '压测-小商家', 1)
    ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

INSERT INTO account (id, tenant_id, username, password_hash, role) VALUES
    (9909011, 901, 'perf_m901_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN'),
    (9909021, 902, 'perf_m902_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN')
    ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1, deleted = 0;

-- 买家：递归 CTE 一次生成 200 行（MySQL 8）
INSERT INTO account (id, tenant_id, username, password_hash, role)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT 9900000 + n, NULL, CONCAT('perf_buyer_', LPAD(n, 4, '0')),
       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'BUYER'
FROM seq
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1, deleted = 0;

-- ---------- product-inventory：商品、SKU、库存 ----------
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES
    (9010, 901, '压测-大商家爆款 T 恤', 'F1 压测', 2),
    (9020, 902, '压测-小商家手作杯', 'F1 压测', 2)
    ON DUPLICATE KEY UPDATE status = 2, deleted = 0;

INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10)
SELECT 90100 + n, 901, 9010, CONCAT('PERF-TEE-', n), JSON_OBJECT('尺码', CONCAT('S', n)), 9900, 1 FROM seq
ON DUPLICATE KEY UPDATE status = 1, price = VALUES(price), deleted = 0;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
VALUES (90201, 902, 9020, 'PERF-MUG-1', JSON_OBJECT('颜色', '蓝'), 3900, 1)
ON DUPLICATE KEY UPDATE status = 1, price = VALUES(price), deleted = 0;

-- 库存重置到初始值（reserved 清零）。检查脚本按这两个初始值核对"初始 - 当前可售 = 预占中 + 已确认"
INSERT INTO inventory (tenant_id, sku_id, available, reserved)
SELECT tenant_id, id, IF(tenant_id = 901, 1000000, 100000), 0 FROM sku WHERE id BETWEEN 90101 AND 90110 OR id = 90201
ON DUPLICATE KEY UPDATE available = VALUES(available), reserved = 0, deleted = 0;
