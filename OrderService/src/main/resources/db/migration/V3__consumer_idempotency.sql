-- B2：消费端幂等 + 演示下游"商家新订单通知"（方案 A：消费者暂放 order 服务，realtime 任务时迁走）

-- 消费去重表：和业务处理写在同一个本地事务里。唯一键冲突 = 这个消费组已经处理过这个事件
CREATE TABLE consumed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    consumer_group VARCHAR(64) NOT NULL COMMENT '不同消费组各自去重：同一事件，通知和索引是两个下游，各处理一次',
    event_id       BIGINT      NOT NULL COMMENT '生产方的事件 ID，不是 RocketMQ msgId（重发时 msgId 会变）',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_event (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费去重记录';

-- 故意不给 order_no 加唯一键：幂等由 consumed_event 负责，这里重复了就说明幂等失效，测试能直接看出来
CREATE TABLE merchant_order_notice (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL COMMENT '通知给哪个商家',
    order_no    BIGINT      NOT NULL,
    buyer_id    BIGINT      NOT NULL,
    pay_amount  BIGINT      NOT NULL COMMENT '分',
    event_id    BIGINT      NOT NULL,
    read_status TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未读 / 1 已读',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_created (tenant_id, created_at),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商家新订单通知';
