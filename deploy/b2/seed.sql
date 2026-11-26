-- Product data for manual experiments: merchant 7, product 1, SKU 1, stock 100. Safe to re-run
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES (1, 7, 'Test T-shirt', 'Kill experiment', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (1, 7, 1, 'TEE-W-L', '{"color":"white","size":"L"}', 9900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (7, 1, 100, 0)
  ON DUPLICATE KEY UPDATE available = 100;
