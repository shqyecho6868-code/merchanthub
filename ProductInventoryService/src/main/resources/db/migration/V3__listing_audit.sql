-- Product listing approval. The approval engine lives in user-tenant; here we only record "which round, which instance, what result", plus a status history.
ALTER TABLE product
    ADD COLUMN audit_round        INT          NOT NULL DEFAULT 0 COMMENT 'Listing review submission count; result write-back uses WHERE audit_round = round in the message, so a late message from a previous round cannot change this one',
    ADD COLUMN audit_instance_id  VARCHAR(64)  COMMENT 'Flowable process instance ID of this round (filled after start succeeds; NULL = not started yet, the compensation scan will start it again)',
    ADD COLUMN audit_submitted_at DATETIME(3)  COMMENT 'Submission time of this round; the compensation scan uses it to find products under review for too long',
    ADD COLUMN reject_reason      VARCHAR(255) COMMENT 'Latest rejection reason',
    ADD KEY idx_status_audit_submitted (status, audit_submitted_at);

-- Product status history (industry practice: log every status change, same as order_status_log)
CREATE TABLE product_status_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    product_id  BIGINT       NOT NULL,
    from_status TINYINT      NOT NULL,
    to_status   TINYINT      NOT NULL,
    event       VARCHAR(32)  NOT NULL COMMENT 'SUBMIT_LISTING / LISTING_APPROVED / LISTING_REJECTED',
    audit_round INT          NOT NULL,
    source      VARCHAR(16)  NOT NULL COMMENT 'API (merchant submission) / MQ (approval result message) / COMPENSATE (compensation scan lookup)',
    remark      VARCHAR(255),
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Product status history';
