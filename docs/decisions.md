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
