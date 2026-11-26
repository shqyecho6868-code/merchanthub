-- Experiment: one order for each of two merchants (written directly, bypassing the order flow). Safe to re-run
--   Merchant 7's order: 7000000000000000001
--   Merchant 8's order: 8000000000000000001
-- Both buyers are 50001; status 20 (paid), so timeout close doesn't touch them
USE mh_order;
INSERT INTO orders (tenant_id, order_no, buyer_id, status, total_amount, discount_amount, pay_amount, expire_at, paid_at)
VALUES (7, 7000000000000000001, 50001, 20, 9900, 0, 9900, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, UTC_TIMESTAMP(3)),
       (8, 8000000000000000001, 50001, 20, 19900, 0, 19900, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE deleted = 0;
SELECT tenant_id, order_no, buyer_id, status, pay_amount FROM orders
WHERE order_no IN (7000000000000000001, 8000000000000000001);
