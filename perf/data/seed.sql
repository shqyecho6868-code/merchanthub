-- Load-test data (idempotent). Prerequisite: user-tenant / product-inventory have started at least once (Flyway created the tables).
--   ./perf/data/seed.sh            (= run this file + delete orders / payments / reservations from the previous round)
--
-- Two merchants, deliberately asymmetric, to observe things like "is the small merchant affected when the big one saturates the system":
--   901 big merchant: 10 SKUs, 1,000,000 stock each (never sells out, unless the scenario tests selling out)
--   902 small merchant: 1 SKU, 100,000 stock
-- ID ranges 9xxxx / 99xxxxx don't overlap with the demo data (merchants 7 / 8).
-- 200 buyers: perf_buyer_0001 ~ perf_buyer_0200, all with password password123 (same BCrypt hash as the demo seed accounts)

-- ---------- user-tenant: tenants and accounts ----------
USE mh_user;
INSERT INTO tenant (id, name, status) VALUES (901, 'Perf Big Merchant', 1), (902, 'Perf Small Merchant', 1)
    ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

INSERT INTO account (id, tenant_id, username, password_hash, role) VALUES
    (9909011, 901, 'perf_m901_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN'),
    (9909021, 902, 'perf_m902_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN')
    ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1, deleted = 0;

-- Buyers: a recursive CTE generates 200 rows at once (MySQL 8)
INSERT INTO account (id, tenant_id, username, password_hash, role)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT 9900000 + n, NULL, CONCAT('perf_buyer_', LPAD(n, 4, '0')),
       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'BUYER'
FROM seq
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1, deleted = 0;

-- ---------- product-inventory: products, SKUs, stock ----------
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES
    (9010, 901, 'Perf Best-Selling T-Shirt', 'Load test', 2),
    (9020, 902, 'Perf Handmade Mug', 'Load test', 2)
    ON DUPLICATE KEY UPDATE status = 2, deleted = 0;

INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10)
SELECT 90100 + n, 901, 9010, CONCAT('PERF-TEE-', n), JSON_OBJECT('size', CONCAT('S', n)), 9900, 1 FROM seq
ON DUPLICATE KEY UPDATE status = 1, price = VALUES(price), deleted = 0;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
VALUES (90201, 902, 9020, 'PERF-MUG-1', JSON_OBJECT('color', 'blue'), 3900, 1)
ON DUPLICATE KEY UPDATE status = 1, price = VALUES(price), deleted = 0;

-- Reset stock to initial values (reserved = 0). The check script uses these to verify "initial - available = reserved + confirmed"
INSERT INTO inventory (tenant_id, sku_id, available, reserved)
SELECT tenant_id, id, IF(tenant_id = 901, 1000000, 100000), 0 FROM sku WHERE id BETWEEN 90101 AND 90110 OR id = 90201
ON DUPLICATE KEY UPDATE available = VALUES(available), reserved = 0, deleted = 0;
