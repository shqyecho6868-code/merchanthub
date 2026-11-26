-- Approval experiment data. Start user-tenant once first (Flyway creates the mh_user tables, Flowable creates the ACT_* tables), then run:
--   docker exec -i mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/d1/seed.sql
-- Every account's password is password123 (BCrypt from htpasswd -bnBC 10; Spring's BCryptPasswordEncoder also accepts the $2y$ prefix)

USE mh_user;
INSERT INTO tenant (id, name, status) VALUES (7, 'Seven Digital', 1), (8, 'Eight Apparel', 1)
    ON DUPLICATE KEY UPDATE status = 1;
INSERT INTO account (id, tenant_id, username, password_hash, role) VALUES
    (1,    NULL, 'platform_admin', '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'PLATFORM_ADMIN'),
    (2001, 7,    'm7_admin',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN'),
    (2002, 7,    'm7_staff',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_STAFF'),
    (3001, 8,    'm8_admin',       '$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2', 'MERCHANT_ADMIN')
    ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 1;

USE mh_product;
-- Merchant 7's draft product 1001 "Flagship Phone"; re-running resets it to draft (handy for repeating the experiment)
INSERT INTO product (id, tenant_id, name, status) VALUES (1001, 7, 'Flagship Phone', 0)
    ON DUPLICATE KEY UPDATE status = 0, audit_round = 0, audit_instance_id = NULL, reject_reason = NULL, audit_submitted_at = NULL;
