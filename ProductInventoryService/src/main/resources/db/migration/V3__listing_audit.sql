-- D1：商品上架审批。审批引擎在 user-tenant，这里只记"第几轮、哪个实例、结果"，并留状态流水。
ALTER TABLE product
    ADD COLUMN audit_round        INT          NOT NULL DEFAULT 0 COMMENT '第几次提交上架审核；回写结果时 WHERE audit_round = 消息里的轮次，上一轮的迟到消息改不了这一轮',
    ADD COLUMN audit_instance_id  VARCHAR(64)  COMMENT '本轮审批的 Flowable 流程实例 ID（发起成功后回填；为空 = 还没发起成功，补偿扫描会重新发起）',
    ADD COLUMN audit_submitted_at DATETIME(3)  COMMENT '本轮提交时间，补偿扫描按它找"审核中太久"的商品',
    ADD COLUMN reject_reason      VARCHAR(255) COMMENT '最近一次驳回原因',
    ADD KEY idx_status_audit_submitted (status, audit_submitted_at);

-- 商品状态流水（行业规范：每次状态变更写流水，同 order_status_log）
CREATE TABLE product_status_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    product_id  BIGINT       NOT NULL,
    from_status TINYINT      NOT NULL,
    to_status   TINYINT      NOT NULL,
    event       VARCHAR(32)  NOT NULL COMMENT 'SUBMIT_LISTING / LISTING_APPROVED / LISTING_REJECTED',
    audit_round INT          NOT NULL,
    source      VARCHAR(16)  NOT NULL COMMENT 'API（商家提交）/ MQ（审批结果消息）/ COMPENSATE（补偿扫描反查）',
    remark      VARCHAR(255),
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品状态流水';
