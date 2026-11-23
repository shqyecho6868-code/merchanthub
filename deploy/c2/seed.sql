-- C2 压测用商品：商家 7（大商家）SKU 1、商家 8（小商家）SKU 2，库存都给足 100000，避免"库存不足"干扰限流观察。可重复执行
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES (1, 7, '实验 T 恤', 'C2 大商家', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (1, 7, 1, 'TEE-W-L', '{"颜色":"白","尺码":"L"}', 9900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (7, 1, 100000, 0)
  ON DUPLICATE KEY UPDATE available = 100000;

INSERT INTO product (id, tenant_id, name, description, status) VALUES (2, 8, '小店马克杯', 'C2 小商家', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (2, 8, 2, 'MUG-01', '{"颜色":"蓝"}', 3900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (8, 2, 100000, 0)
  ON DUPLICATE KEY UPDATE available = 100000;
