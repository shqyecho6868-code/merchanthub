-- Products for the rate-limit load test: merchant 7 (big) SKU 1, merchant 8 (small) SKU 2, both with 100000 in stock so "out of stock" doesn't muddy the rate-limit results. Safe to re-run
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES (1, 7, 'Test T-shirt', 'Big merchant', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (1, 7, 1, 'TEE-W-L', '{"color":"white","size":"L"}', 9900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (7, 1, 100000, 0)
  ON DUPLICATE KEY UPDATE available = 100000;

INSERT INTO product (id, tenant_id, name, description, status) VALUES (2, 8, 'Small Shop Mug', 'Small merchant', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (2, 8, 2, 'MUG-01', '{"color":"blue"}', 3900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (8, 2, 100000, 0)
  ON DUPLICATE KEY UPDATE available = 100000;
