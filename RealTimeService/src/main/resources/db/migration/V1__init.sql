-- realtime 服务的表（库 mh_realtime）。约定同其他服务：DATETIME(3) 存 UTC，created_at / updated_at 由数据库维护。

-- 消费去重（同 order 的 consumed_event）：订单事件重复投递时，商家通知只写一条、推送只发一轮
CREATE TABLE consumed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_id       BIGINT      NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_event (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费去重记录';

-- 商家新订单通知（E1 从 order 迁来）：商家的"通知中心"，推送只是提醒，通知本身持久化在这里
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

-- 离线消息：推送时接收方不在线（路由表里没有节点）或连接刚断，先存这里，上线握手成功后按 id 顺序补发
CREATE TABLE offline_message (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT      NOT NULL COMMENT '消息涉及的商家（买家的订单状态消息也记订单所属商家）',
    receiver_key VARCHAR(64) NOT NULL COMMENT '接收方：merchant:{tenantId} / buyer:{userId}',
    msg_id       VARCHAR(64) NOT NULL COMMENT '推送消息 ID，客户端按它去重',
    type         VARCHAR(32) NOT NULL,
    payload      TEXT        NOT NULL COMMENT '推给客户端的完整消息。用 TEXT 不用 JSON：JSON 列会重排字段、加空格，补发出去的格式就和实时推送不一致了',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0 待补发 / 1 已送达（已写入连接）',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一条消息对同一接收方只存一次：多个节点同时判断"不在线"、MQ 重投都不会存出重复
    UNIQUE KEY uk_receiver_msg (receiver_key, msg_id),
    -- 上线补发：WHERE receiver_key = ? AND status = 0 ORDER BY id
    KEY idx_receiver_status (receiver_key, status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '离线推送消息';
