-- B2 手动实验用的商品数据：商家 7，商品 1，SKU 1，库存 100。可重复执行
USE mh_product;
INSERT INTO product (id, tenant_id, name, description, status) VALUES (1, 7, '实验 T 恤', 'B2 kill 实验', 2)
  ON DUPLICATE KEY UPDATE status = 2;
INSERT INTO sku (id, tenant_id, product_id, sku_code, spec_json, price, status)
  VALUES (1, 7, 1, 'TEE-W-L', '{"颜色":"白","尺码":"L"}', 9900, 1) ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (7, 1, 100, 0)
  ON DUPLICATE KEY UPDATE available = 100;
