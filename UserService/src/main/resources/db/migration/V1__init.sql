-- Business tables of the user-tenant service (database mh_user). Flowable's ACT_* tables are not here; the engine creates them on startup.
-- Same conventions as other services: time columns DATETIME(3) in UTC, created_at / updated_at maintained by the database, deleted = soft delete.

-- Tenant (merchant). Platform-level table: it is the definition of a tenant itself, so it is not filtered by tenant_id
CREATE TABLE tenant (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    name                  VARCHAR(64) NOT NULL COMMENT 'Shop name',
    status                TINYINT     NOT NULL DEFAULT 1 COMMENT '1 active / 0 disabled',
    source_application_id BIGINT      COMMENT 'Onboarding application it was created from; unique, so approving the same application twice still creates only one tenant',
    created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted               TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_application (source_application_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Tenant (merchant)';

-- Accounts: buyers, merchant admins / staff and platform admins all live in this table, told apart by role + tenant_id
CREATE TABLE account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       COMMENT 'Required for merchant roles; NULL for buyers / platform admins (same rule as the gateway Identity)',
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt (random salt built in)',
    role          VARCHAR(32)  NOT NULL COMMENT 'PLATFORM_ADMIN / MERCHANT_ADMIN / MERCHANT_STAFF / BUYER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 normal / 0 disabled',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- Login names are unique platform-wide; also the final arbiter when two onboarding applications race for the same login name
    UNIQUE KEY uk_username (username),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Account';

-- Merchant onboarding applications. Platform-level table: the merchant doesn't exist yet when applying
CREATE TABLE merchant_application (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    applicant_user_id   BIGINT       NOT NULL COMMENT 'Applicant (logged-in platform account; only they can edit after a rejection)',
    shop_name           VARCHAR(64)  NOT NULL,
    admin_username      VARCHAR(64)  NOT NULL COMMENT 'Login name of the merchant admin created on approval',
    admin_password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt only, never plaintext',
    status              TINYINT      NOT NULL DEFAULT 10 COMMENT '10 under review / 20 rejected (awaiting changes) / 30 approved / 40 withdrawn',
    reject_reason       VARCHAR(255),
    submit_count        INT          NOT NULL DEFAULT 1 COMMENT 'Submission count (+1 on resubmission after rejection)',
    process_instance_id VARCHAR(64)  COMMENT 'Flowable process instance ID; resubmission after rejection keeps the same instance',
    tenant_id           BIGINT       COMMENT 'Merchant ID created on approval',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_applicant (applicant_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Merchant onboarding application';

-- Product listing approvals: one submission (one audit_round of a product) = one row = one process instance
CREATE TABLE listing_approval (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    audit_round         INT          NOT NULL COMMENT 'Submission round on the product side',
    product_name        VARCHAR(128) NOT NULL COMMENT 'Shown in the task list; the reviewer sees the name as submitted',
    initiator_user_id   BIGINT,
    process_instance_id VARCHAR(64)  NOT NULL,
    status              TINYINT      NOT NULL DEFAULT 10 COMMENT '10 in review / 30 approved / 40 rejected',
    reject_reason       VARCHAR(255),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- Idempotent start: Flowable doesn't guarantee a unique businessKey; when product retries / compensation starts it again, this unique key keeps a single instance
    UNIQUE KEY uk_product_round (tenant_id, product_id, audit_round),
    KEY idx_process_instance (process_instance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Product listing approval';

-- Outbox for approval results: written in the same transaction as "process ended" (same structure as order_outbox)
CREATE TABLE approval_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL COMMENT 'Unique event ID (snowflake); unchanged on resend',
    biz_key       VARCHAR(64)  NOT NULL COMMENT 'e.g. listing:7:1001:2; set as message keys for troubleshooting',
    topic         VARCHAR(64)  NOT NULL,
    tag           VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 pending / 1 sent / 2 failed (retry limit exceeded)',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL COMMENT 'Earliest time it may be sent (again); also the lease deadline while sending',
    last_error    VARCHAR(512),
    sent_at       DATETIME(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_next_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Approval result outbox';
