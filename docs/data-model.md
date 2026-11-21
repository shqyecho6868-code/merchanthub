# MerchantHub 数据模型（A3）

> A3 只做设计；DDL 从 B1 起落地，字段有调整时回写本文档（见第 6 节变更记录）。
> DDL 源文件：`ProductInventoryService/src/main/resources/db/schema.sql`、`OrderService/src/main/resources/db/schema.sql`。
> 讲解过程和评审记录见 `docs/notes/A3-业务建模.md`。

## 1. 全局约定

| 约定 | 说明 |
|---|---|
| 公共字段 | 每张业务表都有 `tenant_id`、`created_at`、`updated_at`、`deleted`（逻辑删除），下文表格里不重复列出 |
| 金额 | `bigint`，单位分。避免浮点误差，这是行业通行做法 |
| 业务单号 | `order_no`、`pay_no` 用雪花算法生成，不用自增 ID：自增 ID 会暴露业务量，分库以后也不全局唯一 |
| 主键 | `id bigint`，只在内部使用；对外只暴露业务单号 |
| 租户 | `tenant_id` = 商家。买家是平台级用户，用 `buyer_id` 表示，不属于任何租户 |
| 一单一商家 | 本项目没有购物车，一个订单只属于一个商家，不做跨店拆单 |
| 时间 | `DATETIME(3)`，一律存 UTC：JDBC 连接把会话时区固定为 UTC（`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`）。`created_at` / `updated_at` 由数据库默认值维护（B1） |
| 分库 | 每个服务一个库：product-inventory → `mh_product`，order → `mh_order`。不跨库 join，不共用本地事务（B1） |

## 2. ER 关系

```mermaid
erDiagram
    product ||--|{ sku : "一个商品多个规格"
    sku ||--|| inventory : "一个 SKU 一行库存"
    inventory ||--o{ inventory_reservation : "按订单记录预占"
    orders ||--|{ order_item : "一单多行"
    order_item ||--|| trade_snapshot : "每行一份快照"
    orders ||--o{ payment : "一单多次支付尝试"
    orders ||--|{ order_status_log : "每次状态变更一条"
    sku ||--o{ order_item : "被购买"
    orders ||--|| order_outbox : "同一事务写一条待发事件（B2）"
```

## 3. 表结构

### 3.1 product（商品 / SPU）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| name | varchar(128) | 商品名 |
| description | text | 详情描述 |
| main_image | varchar(512) | 主图（MinIO 地址） |
| status | tinyint | 0 草稿 / 1 审核中 / 2 已上架 / 3 已下架 / 4 审核驳回 |

索引：`idx(tenant_id, status)`
设计要点：价格和库存都不放在商品表，因为不同规格的价格、库存可以不一样

### 3.2 sku
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| product_id | bigint | 所属商品 |
| sku_code | varchar(64) | 商家自定义编码 |
| spec_json | json | 规格，如 `{"颜色":"白","尺码":"L"}` |
| price | bigint | 售价（分） |
| status | tinyint | 0 停售 / 1 在售 |

索引：`uk(tenant_id, sku_code)`、`idx(product_id)`
设计要点：`sku_code` 只要求在同一商家内唯一，所以唯一索引带上 `tenant_id`

### 3.3 inventory（库存）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| sku_id | bigint | |
| available | int | 可售数量 |
| reserved | int | 预占数量（已下单、未支付） |

索引：`uk(sku_id)`

| 时刻 | available | reserved | 条件 |
|---|---|---|---|
| 下单：预占 | −n | +n | `available >= n` |
| 支付成功：确认扣减 | 不变 | −n | `reserved >= n` |
| 取消/超时：释放 | +n | −n | `reserved >= n` |

设计要点：库存和 SKU 分开存。库存是写多的热点行，SKU 是读多写少的描述信息，拆开以后两者不会抢同一行锁。`available` 故意不加 UNSIGNED / CHECK，防超卖由条件更新负责（B1）

### 3.3.1 inventory_reservation（库存预占记录，B1 新增）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no | bigint | |
| sku_id | bigint | |
| quantity | int | 预占数量 |
| status | tinyint | 10 RESERVED / 20 CONFIRMED / 30 RELEASED |

索引：`uk(order_no, sku_id)`
设计要点：
- 和库存条件更新在**同一个本地事务**里写入。重复预占（Feign 重试、并发重复请求）会撞唯一键，整个事务回滚，库存不会被扣两次
- 撞唯一键且已有记录与请求一致时返回成功（幂等），不一致则报错
- CONFIRMED / RELEASED 是互斥终态，B2/B3 用 `WHERE status = 10` 做 CAS，保证确认和释放各最多执行一次
- 还用于补偿：库存占了、订单没建成时，B2/B3 按 order_no 找到悬空的预占并释放

### 3.4 orders（订单）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no | bigint | 业务订单号 |
| buyer_id | bigint | 买家 |
| status | tinyint | 见第 4 节 |
| total_amount | bigint | 商品总额 |
| discount_amount | bigint | 优惠总额 |
| pay_amount | bigint | 应付 = 总额 − 优惠 |
| expire_at | datetime | 支付截止时间 |
| paid_at / shipped_at / completed_at / closed_at | datetime | 关键时间点 |
| close_reason | varchar(64) | BUYER_CANCEL / PAY_TIMEOUT |

索引：`uk(order_no)`、`idx(tenant_id, status, created_at)`、`idx(buyer_id, created_at)`
表名用 `orders`，因为 `order` 是 SQL 关键字

### 3.5 order_item（订单明细）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no | bigint | |
| product_id / sku_id | bigint | |
| product_name | varchar(128) | 快照冗余 |
| sku_spec | varchar(256) | 快照冗余，如"白色 / L" |
| unit_price | bigint | 快照冗余，下单时的单价 |
| quantity | int | |
| total_amount | bigint | 单价 × 数量 |
| discount_amount | bigint | 分摊到这一行的优惠 |
| pay_amount | bigint | 这一行实付 |
| snapshot_id | bigint | 指向 trade_snapshot |

索引：`idx(order_no)`

### 3.6 payment（支付单）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| pay_no | bigint | 支付单号 |
| order_no | bigint | 关联订单（不唯一） |
| buyer_id | bigint | |
| amount | bigint | 本次支付金额 |
| channel | varchar(32) | 本项目只有 MOCK |
| status | tinyint | 0 待支付 / 1 成功 / 2 失败 / 3 已关闭 |
| channel_trade_no | varchar(64) | 渠道流水号，回调时写入（待支付时 NULL）；唯一键兜底 |
| paid_at | datetime | |
| success_attempt | varchar(36) | B3 新增：赢得 待支付→成功 CAS 的回调尝试 ID，事务回查只提交这次回调的半消息 |
| tenant_id / created_at / updated_at / deleted | | 公共字段（B3 落表时补齐） |

索引：`uk(pay_no)`、`idx(order_no)`、`uk(channel_trade_no)`

### 3.7 order_status_log（订单状态流水）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no | bigint | |
| from_status | tinyint | 创建时为 NULL |
| to_status | tinyint | |
| event | varchar(32) | CREATE / PAY_SUCCESS / BUYER_CANCEL / PAY_TIMEOUT / SHIP / CONFIRM / AUTO_CONFIRM |
| operator_type | varchar(16) | BUYER / MERCHANT / SYSTEM |
| operator_id | bigint | 系统操作时为 0 |
| remark | varchar(256) | |

索引：`idx(order_no, created_at)`
设计要点：只插入、不更新，和订单状态的变更在同一个本地事务里写入

### 3.8 trade_snapshot（交易快照）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no | bigint | |
| sku_id | bigint | |
| content | json | 下单那一刻的完整商品信息：名称、规格、价格、描述、图片 |

索引：`idx(order_no)`

### 3.9 order_outbox（订单事件本地消息表，B2 新增，Flyway V2）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| event_id | bigint UK | 事件唯一 ID（雪花），重发不变，消费端按它幂等 |
| biz_key | varchar(64) | 业务键，这里是 order_no，同时放进消息 keys 方便排查 |
| topic / tag | varchar(64) | 发到哪（只记 Topic，不记下游是谁） |
| payload | json | 事件 JSON：eventId、orderNo、tenantId、buyerId、payAmount、createdAt |
| status | tinyint | 0 待发送 / 1 已发送 / 2 发送失败（超过重试上限，人工处理） |
| retry_count | int | 已失败次数 |
| next_retry_at | datetime(3) | 最早什么时候可以（再）发送；发送过程中兼作租约截止时间 |
| last_error | varchar(512) | 最近一次失败原因 |
| sent_at | datetime(3) | |

索引：`uk(event_id)`、`idx(status, next_retry_at)`（扫描条件）
设计要点：和订单在**同一个本地事务**里写入；抢占只推后 `next_retry_at`、不改 status，只有发送成功才改成 1（B2 笔记 7.3）

### 3.10 consumed_event（消费去重记录，B2 新增，Flyway V3）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| consumer_group | varchar(64) | 不同消费组各自去重 |
| event_id | bigint | 生产方的事件 ID，不是 RocketMQ msgId |

索引：`uk(consumer_group, event_id)`
设计要点：和消费者的业务处理写在**同一个本地事务**里，唯一键冲突 = 已处理过

### 3.11 merchant_order_notice（商家新订单通知，B2 新增，Flyway V3）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| order_no / buyer_id / pay_amount / event_id | bigint | 来自 OrderCreated 事件 |
| read_status | tinyint | 0 未读 / 1 已读 |

索引：`idx(tenant_id, created_at)`、`idx(order_no)`
设计要点：B2 演示下游（方案 A，暂放 order 服务，realtime 任务时迁走）；`order_no` 故意不加唯一键，幂等由 `consumed_event` 负责，重复了测试能直接看出来

## 4. 订单状态机

| 编码 | 状态 | 类型 |
|---|---|---|
| 10 | PENDING_PAYMENT 待支付 | |
| 20 | PAID 已支付 | |
| 30 | SHIPPED 已发货 | |
| 40 | COMPLETED 已完成 | 终态 |
| 50 | CLOSED 已关闭 | 终态 |

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: CREATE 提交订单（买家）
    PENDING_PAYMENT --> PAID: PAY_SUCCESS 支付成功回调（系统）
    PENDING_PAYMENT --> CLOSED: BUYER_CANCEL 买家取消（买家）
    PENDING_PAYMENT --> CLOSED: PAY_TIMEOUT 支付超时（系统）
    PAID --> SHIPPED: SHIP 发货（商家）
    SHIPPED --> COMPLETED: CONFIRM 确认收货（买家）
    SHIPPED --> COMPLETED: AUTO_CONFIRM 发货 7 天后自动收货（系统）
    COMPLETED --> [*]
    CLOSED --> [*]
```

### 4.1 流转和副作用

| 事件 | 流转 | 触发者 | 库存 | 其他 |
|---|---|---|---|---|
| CREATE | → 10 | 买家 | 预占 | 写快照，发送超时关单延迟消息 |
| PAY_SUCCESS | 10 → 20 | 系统（支付回调） | 确认扣减 | 支付单 → 成功 |
| BUYER_CANCEL | 10 → 50 | 买家 | 释放 | 关闭未完成的支付单 |
| PAY_TIMEOUT | 10 → 50 | 系统（延迟消息） | 释放 | 关闭未完成的支付单 |
| SHIP | 20 → 30 | 商家 | — | 开始 7 天自动收货计时 |
| CONFIRM / AUTO_CONFIRM | 30 → 40 | 买家 / 系统 | — | |

### 4.2 不合法流转

| 流转 | 原因 |
|---|---|
| 50 → 任何 | 终态。超时关单以后支付回调才到达时，CAS 会失败，订单不能"复活"，要走对账补偿 |
| 40 → 任何 | 终态 |
| 10 → 30 | 跳过了支付 |
| 30 → 50 | 已经发货，不能直接取消（现实中要走退货退款，本项目不做） |
| 20 → 50 | 已经付款，取消必须同时退款，本项目不做退款 |
| 任何往回走的流转 | 状态只能沿定义的方向前进 |

实现方式：`UPDATE orders SET status=? WHERE order_no=? AND status=旧状态`。影响行数为 0 就说明当前状态不对，或者被并发请求抢先改了。改状态和写流水放在同一个事务里

## 5. 设计取舍

| 决策 | 选了 | 没选 | 理由 / 代价 |
|---|---|---|---|
| 库存存放 | 独立的 inventory 表 | 在 SKU 表里加 stock 字段 | 热点写和描述信息分开，减少锁竞争；代价是多一次查询 |
| 库存模型 | available + reserved 两个字段 | 只存一个 stock | 能表达"已下单未付款"的中间态，支持超时释放；代价是每次变更都要同时改两个字段 |
| 减库存时机 | 下单预占，支付确认 | 下单直接扣减 / 付款时扣减 | 下单直接扣：恶意下单不付款会把库存占光；付款时扣：付款时才发现没货，会超卖。预占 + 超时释放兼顾两者 |
| 订单与支付 | 两张表，一对多 | 支付字段放进订单表 | 一个订单可以多次尝试支付；回调按 `pay_no` / `channel_trade_no` 做幂等；代价是多一张表、多一次关联 |
| 快照 | 明细里冗余关键字段 + 独立的快照 JSON 表 | 订单只存 sku_id，展示时再查商品 | 商家改价、改描述、删商品都不影响历史订单；代价是存储有冗余。也可以按内容 hash 去重、多个订单共享一份快照，demo 规模下没有必要 |
| 关闭状态 | 一个 CLOSED + close_reason | CANCELLED / CLOSED 两个终态 | 两者后续处理一样；代价是按原因统计时要查 close_reason |
| 已支付后取消 | 不支持 | 已支付 → 关闭 | 必须同时退款，退款不在本项目范围内；以后做退款时加"退款中"状态 |
| 状态编码 | 10/20/30 间隔编码 | 0/1/2 连续编码 | 以后可以在中间插入新状态（比如 25 退款中） |

## 6. 变更记录

| 任务 | 变更 |
|---|---|
| B3 | product-inventory 改用 Flyway（`schema.sql` → `V1__init.sql`）；新增库 `mh_payment` 与 `payment` 表（Flyway V1），在 3.6 基础上增加 `tenant_id`、`success_attempt`（赢得成功 CAS 的回调尝试 ID，事务回查用，P19）及公共字段；`channel_trade_no` 允许 NULL（待支付时为空，唯一索引允许多个 NULL）；`order_status_log` 无结构变化，B3 起由 `OrderStatusTransitioner` 写入 |
| B2 | order 服务改用 Flyway：原 `schema.sql` → `V1__init.sql`（基线），`V2` 新增 `order_outbox`，`V3` 新增 `consumed_event`、`merchant_order_notice`；`orders.created_at` 改由应用写入下单时刻（与事件里的 createdAt 一致） |
| B1 | 新增 `inventory_reservation`；时间列定为 `DATETIME(3)` + UTC；按服务分库 `mh_product` / `mh_order`；`inventory.available` 不加 UNSIGNED/CHECK |
