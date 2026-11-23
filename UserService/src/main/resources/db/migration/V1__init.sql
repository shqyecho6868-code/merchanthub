-- user-tenant 服务的业务表（库 mh_user，D1）。Flowable 的 ACT_* 表不在这里，由引擎启动时自动建。
-- 约定同其他服务：时间列 DATETIME(3) 存 UTC，created_at / updated_at 由数据库维护，deleted 逻辑删除。

-- 租户（商家）。平台级表：它本身就是"租户"的定义，不再按 tenant_id 过滤
CREATE TABLE tenant (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    name                  VARCHAR(64) NOT NULL COMMENT '店铺名',
    status                TINYINT     NOT NULL DEFAULT 1 COMMENT '1 营业中 / 0 停用',
    source_application_id BIGINT      COMMENT '由哪张入驻申请创建；唯一 → 同一张申请重复执行"通过"也只会建一个租户',
    created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted               TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_application (source_application_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租户（商家）';

-- 账号：买家、商家管理员 / 员工、平台管理员都在这张表，靠 role + tenant_id 区分
CREATE TABLE account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       COMMENT '商家角色必填；买家 / 平台管理员为 NULL（规则同网关 Identity）',
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt（自带随机盐），替换旧 IM 的 MD5 + 固定盐',
    role          VARCHAR(32)  NOT NULL COMMENT 'PLATFORM_ADMIN / MERCHANT_ADMIN / MERCHANT_STAFF / BUYER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 登录名全平台唯一；也是"两张入驻申请抢同一个登录名"时的最终裁决
    UNIQUE KEY uk_username (username),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号';

-- 商家入驻申请。平台级表：申请时商家还不存在
CREATE TABLE merchant_application (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    applicant_user_id   BIGINT       NOT NULL COMMENT '申请人（已登录的平台账号，驳回后只有他能改）',
    shop_name           VARCHAR(64)  NOT NULL,
    admin_username      VARCHAR(64)  NOT NULL COMMENT '通过后开通的商家管理员登录名',
    admin_password_hash VARCHAR(100) NOT NULL COMMENT '只存 BCrypt，不存明文',
    status              TINYINT      NOT NULL DEFAULT 10 COMMENT '10 审核中 / 20 已驳回（待修改）/ 30 已通过 / 40 已撤回',
    reject_reason       VARCHAR(255),
    submit_count        INT          NOT NULL DEFAULT 1 COMMENT '第几次提交（驳回后重提 +1）',
    process_instance_id VARCHAR(64)  COMMENT 'Flowable 流程实例 ID；驳回重提仍是同一个实例',
    tenant_id           BIGINT       COMMENT '通过后创建的商家 ID',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_applicant (applicant_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商家入驻申请';

-- 商品上架审批单：一次提交（product 的一个 audit_round）= 一行 = 一个流程实例
CREATE TABLE listing_approval (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    audit_round         INT          NOT NULL COMMENT 'product 侧第几次提交审核',
    product_name        VARCHAR(128) NOT NULL COMMENT '待办列表里显示；审批看的是提交时的名字',
    initiator_user_id   BIGINT,
    process_instance_id VARCHAR(64)  NOT NULL,
    status              TINYINT      NOT NULL DEFAULT 10 COMMENT '10 审批中 / 30 已通过 / 40 已驳回',
    reject_reason       VARCHAR(255),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 发起幂等：Flowable 不保证 businessKey 唯一，product 重试 / 补偿重复发起时靠这个唯一键只留一个实例
    UNIQUE KEY uk_product_round (tenant_id, product_id, audit_round),
    KEY idx_process_instance (process_instance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品上架审批单';

-- 审批结果的本地消息表：和"流程结束"在同一个事务里写入（结构同 order_outbox，B2）
CREATE TABLE approval_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL COMMENT '事件唯一 ID（雪花），重发不变',
    biz_key       VARCHAR(64)  NOT NULL COMMENT '如 listing:7:1001:2，放进消息 keys 方便排查',
    topic         VARCHAR(64)  NOT NULL,
    tag           VARCHAR(64)  NOT NULL,
    payload       JSON         NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待发送 / 1 已发送 / 2 发送失败（超过重试上限）',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL COMMENT '最早什么时候可以（再）发送；也用作发送中的租约截止时间',
    last_error    VARCHAR(512),
    sent_at       DATETIME(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_next_retry (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批结果本地消息表';
