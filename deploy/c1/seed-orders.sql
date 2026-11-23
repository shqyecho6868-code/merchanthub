-- C1 🧑 实验：两个商家各一张订单（直接写库，不走下单链路）。可重复执行
--   商家 7 的订单：7000000000000000001
--   商家 8 的订单：8000000000000000001
-- 买家都是 50001；状态 20（已支付），不会被超时关单影响
USE mh_order;
INSERT INTO orders (tenant_id, order_no, buyer_id, status, total_amount, discount_amount, pay_amount, expire_at, paid_at)
VALUES (7, 7000000000000000001, 50001, 20, 9900, 0, 9900, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, UTC_TIMESTAMP(3)),
       (8, 8000000000000000001, 50001, 20, 19900, 0, 19900, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE deleted = 0;
SELECT tenant_id, order_no, buyer_id, status, pay_amount FROM orders
WHERE order_no IN (7000000000000000001, 8000000000000000001);
