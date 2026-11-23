-- D1 🧑 实验数据。先启动一次 user-tenant（Flyway 建 mh_user 的表、Flowable 建 ACT_* 表），再执行：
--   docker exec -i mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/d1/seed.sql
-- 所有账号密码都是 password123（BCrypt，htpasswd -bnBC 10 生成；$2y$ 前缀 Spring 的 BCryptPasswordEncoder 也认）

USE mh_user;
INSERT INTO tenant (id, name, status) VALUES (7, '七号数码', 1), (8, '八号服饰', 1)
    ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO account (id, tenant_id, username, password_hash, role) VALUES
    (1,    NULL, 'platform_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'PLATFORM_ADMIN'),
    (2001, 7,    'm7_admin',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN'),
    (2002, 7,    'm7_staff',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_STAFF'),
    (3001, 8,    'm8_admin',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN')
    ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1;

USE mh_product;
-- 商家 7 的草稿商品 1001"旗舰手机"；重复执行会把它重置回草稿（方便重做实验）
INSERT INTO product (id, tenant_id, name, status) VALUES (1001, 7, '旗舰手机', 0)
    ON DUPLICATE KEY UPDATE status = 0, audit_round = 0, audit_instance_id = NULL, reject_reason = NULL, audit_submitted_at = NULL;
