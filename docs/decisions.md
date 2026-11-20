# 决策记录

> 格式：选了什么 / 没选什么 / 为什么 / 代价。详细背景见 `migration-map.md` 第 6 节。

## D1｜服务划分为 8 个（A1，2026-11-16）
- **选**：gateway、user-tenant、product-inventory、order、payment、realtime、ai、common
- **没选**：单独的 workflow 服务、单独的 search/sync 服务
- **为什么**：demo 规模下，每多一个服务就多一套部署和一条远程链路；审批和搜索各只服务一两个场景
- **代价**：user-tenant、order 职责偏重；以后规模变大再拆

## D2｜product 与 inventory 合并（A1）
- **选**：合并为 product-inventory，包内分 `product` / `inventory` 子域
- **没选**：拆成两个服务
- **为什么**：库存挂在 SKU 上，“建 SKU + 初始化库存”需在同一本地事务中完成；拆分主要是为了独立扩容、隔离热点，本项目不存在
- **代价**：库存成为热点时无法单独扩容；靠子域边界保留以后拆的可能

## D3｜order 与 payment 分开（A1）
- **选**：分开
- **没选**：合并成交易服务
- **为什么**：订单与支付单是 1:N（多次支付尝试）；生命周期和意图不同；payment 要处理外部回调、验签、对账；两者间的最终一致性是学习重点
- **代价**：多一条跨服务链路，存在“支付成功但订单未更新”的一致性窗口，需要 MQ 幂等消费 + 对账兜底

## D4｜Flowable 引擎放 user-tenant（A1）
- **选**：引擎只放 user-tenant；product 用 Feign 发起上架审批，结果经 MQ 回写
- **没选**：独立审批服务（两类审批都要远程，成本最高）；放 product（入驻审批归属错误、反向依赖）；两个服务各嵌一个引擎（两套引擎表，混乱）
- **为什么**：审批人和角色都在 user-tenant，候选人/组可直接映射；入驻审批回写租户状态是本地事务
- **代价**：上架审批跨服务，须保证可靠投递、消费幂等（CAS）、防乱序（带流程实例 ID）

## D5｜AI 向量库用 ES 8 `dense_vector`，不用 pgvector（A1）
- **选**：ES 8
- **没选**：pgvector（技术栈表外组件）、Redis Stack
- **为什么**：ES 已为订单查询引入，不新增组件；kNN 查询内可直接做 `tenant_id` 过滤，租户隔离在检索层完成
- **代价**：与订单查询共用集群、资源互相影响；HNSW 索引吃内存；LangChain4j 集成的兼容性待 A2 核实

## D6｜Kafka 全量替换为 RocketMQ；ShedLock 由 XXL-Job 替代（A1）
- **选**：只保留 RocketMQ；超时取消主路径用 RocketMQ 延迟消息，XXL-Job 做兜底扫描和对账
- **没选**：Kafka + RocketMQ 并存；沿用“Redis ZSET 时间轮 + ShedLock 每秒扫描”
- **为什么**：只维护一套 MQ；RocketMQ 原生支持事务消息和延迟消息，正好对应下单一致性和超时取消；旧 Kafka/ShedLock 用法全部随 IM 业务删除，无迁移成本
- **代价**：要重新学 RocketMQ 的消费模型和事务消息回查

## D7｜Canal → ES 同步组件放 order 内部（A1）
- **选**：放在 order 服务内
- **没选**：单独的 sync 进程
- **为什么**：只同步订单表
- **代价**：同步线程和 order 服务同生共死

## D8｜依赖结构：根 pom 只管版本，Common 重依赖设为 optional（A2，2026-11-16）
- **选**：根 pom 只做 `dependencyManagement`（外加 lombok/test）；Common 的 web/MyBatis/AOP 标为 optional，相关 Bean 用 `@ConditionalOnClass` 按需装配
- **没选**：Gateway 用 `<exclusions>` 排除（治标，每个新服务都要排一遍）；拆分 common-core / common-web（结构最清晰，但超出 A2 范围，建新服务时再评估）
- **为什么**：各服务 pom 明确写出自己用到的依赖；Gateway（WebFlux）不再被带上 Servlet 栈
- **代价**：各服务 pom 变长；Common 新增 Bean 时要记得判断是否需要加条件装配

## D9｜版本：SCA 2025.0.0.0 + Spring Cloud 2025.0.0 + Boot 3.5.11（A2）
- **依据**：sca.aliyun.com 官方版本说明（GitHub wiki 已过时）。中间件服务端与客户端对齐：Nacos v3.0.3、RocketMQ 5.3.1

## D10｜RocketMQ `brokerIP1 = host.docker.internal`（A2）
- **选**：用主机名，容器侧靠 `extra_hosts: host-gateway`，宿主机侧靠 `/etc/hosts`
- **没选**：`127.0.0.1`（容器里的 Dashboard 连不上）；局域网 IP（校园网下 IP 会变）
- **代价**：宿主机需要一次性 sudo 修改 `/etc/hosts`

## D11｜测试分层：`*Test` 单元测试 + `*IT` Testcontainers 集成测试（A2+，2026-11-16）
- **选**：surefire 跑 `*Test`（不依赖 Docker，`mvn test` 秒级）；failsafe 跑 `*IT`（Testcontainers 起 MySQL/Redis/RocketMQ，`mvn verify`）；CI 每次 push 跑 `verify`
- **没选**：H2 / 嵌入式 Redis 代替真实中间件（行为和 MySQL 8、Redis 7 不一致，库存 CAS、锁、事务隔离这类关键测试会测不准）；连本机 docker-compose 的中间件跑测试（CI 上没有，且测试数据会污染开发库）；`@Testcontainers(disabledWithoutDocker = true)`（没 Docker 时测试静默跳过，看起来是绿的，违背“测试通过才算完成”）
- **为什么**：镜像版本与 compose 一致，测试环境 = 开发环境；GitHub runner 自带 Docker
- **代价**：集成测试慢（首次拉镜像），本地跑 `verify` 必须开 Docker

## D12｜测试框架版本交给 Spring Boot BOM，根 pom 显式导入但不写版本号（A2+）
- **选**：根 pom `dependencyManagement` 导入 `junit-bom`、`mockito-bom`、`testcontainers-bom`，版本引用 Boot 的属性 `${junit-jupiter.version}` 等（当前 5.12.2 / 5.17.0 / 1.21.4）
- **没选**：自己写死版本号（升级 Boot 时容易与 Boot 自带版本打架，P13 就是这类问题）
- **代价**：想单独升级某个测试框架时，要覆盖 Boot 的属性
- **注**：Testcontainers 1.21.x 没有 RocketMQ 模块，用 `GenericContainer` 自己封装（见 `MiddlewareContainersIT.RocketMqContainer`）

## D13｜JaCoCo 覆盖率：单测 + 集成测试合并统计；门禁按模块开启（A2+）
- **选**：surefire 与 failsafe 共用一个 `jacoco.exec`，`verify` 阶段出报告；`jacoco:check` 默认 `skip`，order/inventory/payment 在自己的 pom 打开 70% 行覆盖率
- **没选**：全局统一卡 70%（旧 IM 模块没有测试，会全部失败）；只统计单测（本项目关键逻辑——库存、状态机 CAS——主要靠集成测试覆盖）
- **代价**：每个模块一份报告，没有全项目汇总报告；CI Summary 页用脚本把各模块 csv 汇总成表

## D14｜引入 Maven Wrapper（A2+）
- **选**：`./mvnw`（only-script 模式，Maven 3.9.11，下载地址用 Maven Central）
- **为什么**：本机没有全局 mvn，CI 和本机用同一个 Maven 版本；only-script 模式不需要提交 jar（`.gitignore` 忽略了 `*.jar`）
- **注**：wrapper 生成时会继承本机 `settings.xml` 的阿里云镜像地址，已改回 Maven Central（GitHub runner 在海外）

## D15｜删除 5 个旧服务的 `contextLoads` 测试（A2+）
- **为什么**：它们要连 Nacos/MySQL/Kafka 才能启动 Spring 容器，CI 上必红；本身不验证任何行为。各服务改造时按新约定重写测试

## D15｜库存：独立 inventory 表，available + reserved 双字段，预占 → 确认 → 释放（A3，2026-11-17）
- **选**：库存单独建表，一个 SKU 一行；下单预占（available−n, reserved+n），支付确认（reserved−n），超时/取消释放（available+n, reserved−n）
- **没选**：在 SKU 表加 stock 字段（热点写和描述信息抢同一行锁）；下单直接扣（恶意下单不付款能占光库存）；付款时扣（下单时不占库存，付款时可能无货 → 超卖）
- **代价**：多一个中间态，需要超时任务（RocketMQ 延迟消息）和幂等的预占/释放

## D16｜交易快照分两层：明细冗余关键字段 + trade_snapshot 存完整 JSON（A3）
- **选**：order_item 冗余商品名、规格、单价（列表页直接用）；trade_snapshot.content 存完整商品信息（点“查看快照”才读）
- **没选**：订单只存 sku_id、展示时回查商品（商家改价、改描述、删商品后历史订单失真）；全部塞进明细（明细表变胖，列表查询变慢）；按内容 hash 去重共享快照（demo 规模下没有必要）
- **代价**：存储冗余；每行明细一份快照

## D17｜订单状态机：5 个状态，CLOSED 合并取消与超时，不支持已支付后取消（A3）
- **选**：10 待支付 / 20 已支付 / 30 已发货 / 40 已完成 / 50 已关闭；买家取消和支付超时都进 CLOSED，用 `close_reason` 区分；状态用间隔编码
- **没选**：CANCELLED / CLOSED 两个终态（后续处理一样）；已支付 → 关闭（必须同时退款，退款不在范围内，只改状态不退钱比没有这条流转更危险）
- **为什么**：状态少，状态机简单；终态不可逆，保证“关单后回调到达”时 CAS 失败，交给对账
- **代价**：按原因统计要查 close_reason；商家无货、超时不发货的场景暂时无法处理

## D18｜待支付 → 已支付由支付成功回调触发，不由买家“点付款”触发（A3）
- **选**：买家点付款只创建支付单；渠道回调成功后才推进订单状态
- **为什么**：支付可能失败、可能重试，一个订单对应多张支付单（payment.order_no 不唯一）；回调按 `channel_trade_no` 唯一索引做幂等
- **代价**：订单状态依赖异步回调，存在一致性窗口，需要对账兜底

## D19｜业务单号用雪花算法，主键 id 只在内部使用（A3）
- **选**：`order_no`、`pay_no` 由雪花算法生成，对外暴露；表内另有 `id` 主键
- **没选**：直接用自增 id 作为订单号（暴露业务量、分库后不全局唯一）
- **代价**：多一个唯一索引；雪花 workerId 分配问题（pitfalls P1）要在订单号落地前修复

## D20｜雪花 workerId：启动时从 Redis 领槽位，租约 + 续期（B1，2026-11-18）
- **选**：10 位 workerId+dataCenterId = 1024 个槽位；启动时依次 `SET mh:snowflake:slot:{n} 实例ID NX EX 90`，后台每 30s 比对后续期（Lua compare-and-expire）；用本地截止时间判断租约是否有效，过期或被抢走即停止发号；全局一个命名空间
- **没选**：配置文件手写 workerId（扩容易配重，就是旧代码的问题）；ZooKeeper 顺序节点（不为此引入 ZK）；MySQL 表领号（过期回收要自己写）；Nacos 实例序号（没有稳定序号）
- **代价**：启动强依赖 Redis（宁可起不来也不带重复号运行）；时钟回拨只抛异常不处理；旧代码通过静态门面 `SnowflakeUtil` 兼容

## D21｜下单事务边界：inventory 一个本地事务 + order 一个本地事务，中间缺口留给补偿（B1）
- **选**：按 D2 保持两个服务、两个库（`mh_product` / `mh_order`）。inventory 侧一个本地事务完成"多 SKU 预占 + 写预占记录"（全占或全不占）；order 侧一个本地事务完成"订单 + 明细 + 快照 + 流水"；远程调用放在任何事务之外
- **没选**：Seata（CLAUDE.md 不引入）；两服务共库共事务（破坏服务边界）；order 整个下单方法加 @Transactional（远程调用期间占着连接，且回滚不了远程预占）
- **代价**：预占成功、落单失败 / Feign 超时时出现悬空预占，B1 不处理，B2/B3 按预占记录补偿释放

## D22｜防超卖：数据库条件更新，按 sku_id 排序加锁（B1）
- **选**：`UPDATE inventory SET available=available-?, reserved=reserved+? WHERE sku_id=? AND tenant_id=? AND available>=?`，影响 0 行即库存不足、主动抛异常回滚；多 SKU 按 sku_id 升序更新
- **没选**：Redis Lua 扣减（CLAUDE.md 明确不做，与红包同构；且 Redis 与 MySQL 之间又多一层一致性问题）；`SELECT ... FOR UPDATE` 再在 Java 判断（多一次往返、锁持有更久，效果相同）；先 SELECT 再 UPDATE 不加锁（会超卖且丢失更新）；乐观锁版本号（热点行上大量重试失败）
- **代价**：热点 SKU 的请求在同一行锁上排队，吞吐受限于单行更新速度；超大并发场景需要另做（本项目不做秒杀）

## D23｜库存预占记录表 inventory_reservation，唯一键 (order_no, sku_id)，重复请求幂等返回成功（B1）
- **选**：预占时同事务写记录，先插记录（幂等闸门）再扣库存；状态 10 RESERVED / 20 CONFIRMED / 30 RELEASED；同 order_no 重复请求：内容一致返回成功，不一致返回 RESERVATION_CONFLICT；用 TransactionTemplate 编程式事务，唯一键冲突在事务回滚后、事务外处理
- **没选**：唯一键只建在 order_no（多 SKU 订单存不下）；重复请求返回失败（第一次其实成功了，会让买家看到失败、库存悬空）；只看订单号存在就返回成功（订单号被错误复用时会掩盖超卖）
- **代价**：多一张表、每个 SKU 多一次插入

## D24｜工程约定：每服务一个库、schema.sql 建表、时间存 UTC、TestSupport 共享容器模块（B1）
- **选**：`spring.sql.init` 执行 `db/schema.sql`（全部 IF NOT EXISTS）；JDBC 会话时区固定 UTC、列用 DATETIME(3)；独立 TestSupport 模块提供单例容器（懒启动、JVM 内复用）和 `IntegrationTestBase`，A2+ 的示例 IT 迁入
- **没选**：Flyway（不在技术栈表；B2 起 order 已改用 Flyway，见 D26）；Common 的 test-jar（不传递 Testcontainers 等 test 依赖）；每个测试类 `@Container` 各起一套（慢）
- **代价**：schema.sql 不能做有版本的表结构变更；TestSupport 多一个模块

## D25｜"订单已创建"事件：事务消息与本地消息表两种都实现，配置二选一，默认本地消息表（B2，2026-11-20）
- **选**：`merchanthub.order.event-mode` = `TX_MESSAGE` / `OUTBOX`，下单最后一步抽象为 `OrderPlacer`。事务消息：免回查时间 30s > 本地事务超时 5s，回查端对"订单查不到但消息未满 30s"返回 UNKNOW（第二道防线），查库失败返回 UNKNOW，启动预取 Topic 路由。本地消息表：订单与 outbox 同事务，afterCommit 异步快路径 + `@Scheduled` 扫描兜底，抢占只推后 `next_retry_at` 租约、成功才改状态，指数退避 10s 起封顶 10min，10 次后标失败告警。事件 ID 用自己的雪花 `event_id`，不用 msgId。消费端去重记录与业务同事务。默认 OUTBOX：MQ 不可用时仍能下单，最坏情况可人工重发
- **没选**：Seata（不需要强一致，且全局锁 + TC 组件）；同时开两种方案（重复事件）；在事务内发 MQ；按 msgId / Redis SETNX 去重；rocketmq-spring-boot-starter / Spring Cloud Stream（封装深，看不清三步；兼容性需另核）
- **代价**：事务消息有回查时间上限、MQ 挂了不能下单；本地消息表多一张表、一次 insert、一个扫描任务；两种都需要 B3 对账兜底

## D26｜order 服务引入 Flyway，product-inventory 暂不动（B2）
- **选**：Flyway 11.7.2（Boot 3.5 管理）+ `flyway-mysql`；原 schema.sql 作为 `V1__init.sql` 基线不再修改；`baseline-on-migrate` + `baseline-version: 1` 兼容 B1 建好的本机库（已验证）
- **没选**：继续 `CREATE TABLE IF NOT EXISTS`（不能改已有表）；Liquibase（XML/YAML 变更集更重，本项目只需 SQL 版本化）；两个服务一起切（product-inventory 本任务无表变更，按需再切）
- **代价**：多一个依赖；已执行的 V 文件不能改（校验和）；两个服务暂时用两种建表方式
