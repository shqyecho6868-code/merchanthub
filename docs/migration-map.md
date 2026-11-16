# 迁移地图：千言 IM → 千言商城（多租户电商交易平台）

> A1 产出。只基于当前仓库代码分析（commit `ddc3e4b`），不含表结构与状态图（A3）。
> 状态：**已定稿**（2026-11-16 拍板，决定见 6.3）

---

## 1. 旧模块盘点

| 模块 | 职责 | 核心类 | 中间件 |
|---|---|---|---|
| **Common** | 公共库：统一返回体、异常、JWT、雪花 ID、MyBatis-Plus 分页、请求日志 AOP；以及大量 IM 专用常量/DTO | `BaseResponse` `ResultUtils` `ErrorCode` `BusinessException` `ThrowUtils` `GlobalExceptionHandler` `JwtUtil` `SnowflakeUtil` `MybatisPlusConfig` `LogInterceptor`；IM 专用：`Message` `MessageRequest/Body/Response` `ChatRequest` `validation/*` `FriendStatusEnum` `SessionTypeConstant` 等 | MyBatis-Plus、（间接）LangChain4j |
| **GateWay** | 统一入口：路由 + 全局鉴权过滤器（JWT 解析 + Redis 比对 token） | `AuthorizeFilter` | Spring Cloud Gateway（WebFlux）、Nacos Discovery、Redis |
| **UserService** | 用户注册/登录（邮箱验证码、密码）、双 token 刷新、头像上传（MinIO 预签名）、好友/好友申请/会话/群、内部校验接口、为客户端分配 Netty 节点 | `UserServiceImpl` `UserController` `InternalValidationController` `NettyServiceLocator` `ConsistentHash` `UrlHashLoadBalancer` `OssUtils` `MinioConfig` `EmailUtil`；好友域：`FriendServiceImpl` `ApplyFriendServiceImpl` `FriendRequestExpiration*`（ZSET 时间轮 + ShedLock） `NotificationServiceImpl` | MySQL、Redis（token/验证码/ZSET+Lua）、Kafka、MinIO、Mail、ShedLock(Redis)、Nacos、阿里云短信 SDK |
| **RealTimeService** | Netty WebSocket 长连接：握手鉴权、心跳、上行聊天消息投 Kafka、消费 Kafka 下行推送；转发系统通知 | `NettyService` `WebSocketAuthHeader` `WebSocketHandler` `ChannelManager` `ConsumerMessageService` `SystemNotificationConsumer` `SnowflakeDynamicUtil` | Netty 4.2、Kafka、Redis（离线时间）、OpenFeign、Nacos |
| **OfflineDataService** | 消息落库（消费 store-topic → MySQL）；Canal 监听 message 表 binlog → Redis ZSET 缓存近 7 天消息；离线/历史消息拉取；调 AI 做会话摘要 | `ConsumerOfflineService` `MessageServiceImpl` `CanalClient` `CanalConfig` `MessageController` | Kafka、Canal 1.1.7、MySQL、Redis、OpenFeign |
| **AiService** | LangChain4j 聊天机器人：RAG（本地 md → pgvector）、Redis 对话记忆、工具调用（时间/邮件/写知识库）、MCP 联网搜索、输入护栏、模型调用指标 | `AiChat`（AiServices 接口） `AiChatService` `RagConfig` `EmbeddingStoreConfig` `RagTool` `EmailTool` `SafeInputGuardrail` `RagDataLoader` `Monitor/*` | LangChain4j 1.1 + DashScope(Qwen)、**pgvector(PostgreSQL)**、Redis、MCP、Prometheus |
| **RedPacketService** | 红包发/抢/过期退款，余额与流水 | `RedPacketServiceImpl`（Redis Lua 抢红包） `ExpirationDispatcher` `PreventDuplicateSubmitAspect` `UserServiceClientFallbackFactory` | MySQL、Redis Lua、Kafka、ShedLock、Resilience4j、OpenFeign |

---

## 2. 保留 / 改造 / 删除清单

### 2.1 模块级

| 模块 | 结论 | 理由 |
|---|---|---|
| Common | **改造** | 返回体/异常/工具类是通用骨架，可复用；但混入了 IM 领域对象，且 `GlobalExceptionHandler` 依赖 LangChain4j，需要瘦身解耦 |
| GateWay | **改造** | 网关定位不变；鉴权需升级为“解析身份 → 注入 `X-User-Id / X-Tenant-Id` 头”，并加 Sentinel 限流 |
| UserService | **改造（拆一半删一半）** | 账号/登录/token/MinIO 保留演变为 user-tenant；好友、会话、群、好友申请全部删除 |
| RealTimeService | **改造** | Netty 连接管理、握手鉴权、心跳可复用；上行聊天链路删除，只保留“服务端 → 客户端”下行推送（订单通知） |
| OfflineDataService | **删除（拆出 Canal 能力）** | 消息存储/离线拉取是纯 IM 业务；`CanalClient` 的 binlog 消费骨架改造后用于“订单表 → ES 同步” |
| AiService | **改造** | LangChain4j 装配方式、护栏、指标可复用；知识库要按租户隔离，工具要做权限校验，删除聊天机器人专属能力 |
| RedPacketService | **删除（整体）** | 业务范围明确不做红包；Lua 抢红包、ZSET 时间轮均不迁移 |

### 2.2 关键类级

| 类 | 结论 | 去向 / 理由 |
|---|---|---|
| `BaseResponse` `ResultUtils` `ErrorCode` `BusinessException` `ThrowUtils` | 保留 | common；`ErrorCode` 删 IM/WebSocket 码段，新增订单/库存/支付/租户码段 |
| `GlobalExceptionHandler` | 改造 | common；去掉 `InputGuardrailException` 分支（挪到 ai 服务自己的 Advice），解除 Common 对 LangChain4j 的依赖 |
| `JwtUtil` | 改造 | common；密钥从硬编码常量改为配置；claims 增加 `tenantId / userType / roles` |
| `SnowflakeUtil` / `SnowflakeDynamicUtil` | 改造 | common；workerId 写死为 1（多实例必冲突），`SnowflakeDynamicUtil` 的 static 字段 `@Value` 不会注入（实际恒为 0）。改为启动时按实例分配 workerId，供订单号/支付单号使用 |
| `MybatisPlusConfig` | 改造 | common；增加多租户插件 `TenantLineInnerInterceptor`（多租户重点）、防全表更新插件 |
| `LogInterceptor` | 改造 | common；加 MDC `traceId / tenantId`；**当前会把登录请求的密码打进日志**，需脱敏 |
| `CommonApplication` | 删除 | 库模块不应有启动类 |
| `Message` `MessageRequest/Body/Response` `ChatRequest` `validation/*` IM 枚举/常量 | 删除 | IM 领域对象 |
| `AuthorizeFilter` | 改造 | gateway；校验后注入身份头并**清除客户端自带的同名头**（防伪造）；白名单改成配置 |
| `UserServiceImpl`（注册/登录/刷新） | 改造 | user-tenant；MD5+固定盐 → BCrypt；登录区分买家 / 商家员工 / 平台运营 |
| `OssUtils` `MinioConfig` | 保留 | 先放 user-tenant（头像、商家资质），product 需要传商品图时再评估是否下沉 common |
| `EmailUtil`、验证码逻辑 | 保留 | user-tenant |
| `NettyServiceLocator` `ConsistentHash` `UrlHashLoadBalancer` | 改造 | 迁到 realtime（或 gateway）；“用户 → Netty 节点”路由是实时推送多实例的关键 |
| `InternalValidationController` | 改造 | 思路保留（`/internal` 前缀的 Feign 接口），内容替换为租户/商家/员工校验 |
| 好友/会话/群/好友申请全部类、`FriendRequestExpiration*`、`ShedLockConfig` | 删除 | IM 业务；ShedLock 随之消失 |
| `NettyService` `WebSocketAuthHeader` `ChannelManager` | 改造 | realtime；握手鉴权保留，Channel 绑定 key 改为 `tenantId:userId` |
| `WebSocketHandler` | 改造 | realtime；删上行发消息逻辑，只留心跳和连接清理 |
| `ConsumerMessageService` | 删除 | 单聊/群聊/AI 消息推送 |
| `SystemNotificationConsumer` | 改造 | realtime；“消费 MQ → 找 Channel → 推送”正是订单通知需要的形态；修复离线时 channel 为 null 的 NPE |
| `CanalClient` `CanalConfig` | 改造 | 目标从 Redis ZSET 改为 ES upsert；修复重连时 `subscribe()` 丢失 filter、异常被吞后仍 ack 的问题 |
| `ConsumerOfflineService` `MessageServiceImpl` `MessageController` | 删除 | IM 消息存储/拉取 |
| `AiChat` `AiChatService` | 改造 | ai；拆成“租户知识库客服”和“运营助手”两个 AiServices |
| `RagConfig` `EmbeddingStoreConfig` `RagDataLoader` | 改造 | ai；检索加 `tenantId` 元数据过滤；去掉 `dropTableFirst(true)`（每次启动清空知识库） |
| `RagTool` | 删除 | 让大模型自行写知识库 = 越权入口，与“防越权”目标相反 |
| `EmailTool` `TimeTool` `McpToolConfig` | 删除 | 与电商场景无关；联网搜索 MCP 有数据外泄风险 |
| `SafeInputGuardrail` | 改造 | ai；思路保留，后续扩展为 prompt 注入/越权意图拦截 |
| `Monitor/*` | 保留 | ai；模型调用指标接 Prometheus |
| `PreventDuplicateSubmitAspect` | 删除（思路借鉴） | 红包模块整体删；“setIfAbsent 防重复提交”思路可在下单接口用**幂等 token**重新实现 |
| `UserServiceClientFallbackFactory` | 删除（思路借鉴） | Feign 降级写法可参考，新项目用 Sentinel 实现 |

---

## 3. 新服务划分（已拍板）

| 新服务 | 职责边界 | 演变自 | 主要中间件 |
|---|---|---|---|
| **gateway** | 路由、鉴权、身份头注入（userId/tenantId/userType）、限流 | GateWay | Gateway、Nacos、Redis、Sentinel |
| **user-tenant** | 平台/商家（租户）/员工/买家账号；登录与 token；商家入驻审批；租户与角色校验内部接口 | UserService（账号部分） | MySQL、Redis、MinIO、Mail、Flowable、Nacos |
| **product-inventory** | 商品、SKU、上架审批；库存预占 / 确认扣减 / 释放 | 新建 | MySQL、Redis（可选缓存）、RocketMQ、Flowable 发起方 |
| **order** | 下单、交易快照、订单状态机 + 状态流水、超时取消、订单查询（ES） | 新建；吸收 OfflineDataService 的 Canal 骨架 | MySQL、RocketMQ（事务/延迟消息）、Canal、ES、XXL-Job、Sentinel |
| **payment** | 支付单（一单多次尝试）、模拟支付渠道与回调、对账 | 新建 | MySQL、RocketMQ、XXL-Job |
| **realtime** | Netty 长连接；消费订单事件推送给买家/商家员工 | RealTimeService + UserService 的节点路由 | Netty、RocketMQ、Redis（在线路由表） |
| **ai** | 按租户隔离的知识库客服；防越权运营助手（只读、带租户上下文调用业务接口） | AiService | LangChain4j、ES 8（`dense_vector` 向量检索）、Redis、Prometheus |
| **common** | 返回体、异常、JWT、ID 生成、租户上下文、MDC 日志、MyBatis-Plus 租户插件 | Common | MyBatis-Plus |

### 3.1 关键拆分理由

**order 与 payment 分开**
- 生命周期不同：订单是“交易意图”，支付单是“一次资金动作”；一个订单可对应多次支付尝试（用户换渠道、超时重付），1:N 关系天然是两个聚合。
- 外部边界不同：payment 要接收外部渠道回调，需要验签、幂等、对账，这些和订单状态机无关。
- 一致性是学习重点：两者之间通过 RocketMQ 传“支付成功”事件，正好练“消费端幂等 + 状态 CAS + 对账兜底”。合在一起就变成本地事务，学不到这个点。
- 代价：多一次跨服务调用，多一种最终一致性窗口，需要对账补偿。

**product 与 inventory 放一起**
- 库存挂在 SKU 上，SKU 创建时要初始化库存，二者天然在一个事务里，强一致；拆开就得为“建 SKU + 初始化库存”再做一次分布式一致性，收益不大。
- 对 order 而言，“查 SKU 价格快照 + 预占库存”往往是一次调用，合并后跨服务调用更少。
- 代价/何时拆：真实大厂会拆（库存是高并发热点，要独立扩容、独立缓存）。本项目是小规模 demo，在包结构上分 `product` / `inventory` 两个子域、接口不互相穿透，保留以后拆的可能。

**为什么不单独拆 workflow / search 服务**
- Flowable 引擎只放一处：user-tenant。product 的上架审批通过 Feign 发起流程、通过 MQ 接收审批结果。
- ES 承担订单查询（order 内部）和 AI 向量检索（ai）；Canal 同步组件放 order 内部。

### 3.2 旧能力复用到三大重点

| 重点 | 复用来源 | 怎么用 |
|---|---|---|
| **订单一致性** | Kafka 生产/消费骨架；`FriendRequestExpirationExecutor` 的“条件 UPDATE 只改未终态记录”思路；`CanalClient` 的 getWithoutAck/ack/rollback 骨架 | 换成 RocketMQ 事务消息 + 延迟消息；条件 UPDATE 升级为 `WHERE status = 旧状态` 的 CAS + 状态流水；Canal 同步 ES 保证查询侧最终一致 |
| **多租户** | `AuthorizeFilter`、`JwtUtil`、`InternalValidationController`、`MybatisPlusConfig` | JWT 带 tenantId → 网关注入头 → common 里的 TenantContext + MyBatis-Plus 租户插件自动拼 `tenant_id`；AI 检索按 tenantId 过滤 |
| **实时推送** | `NettyService` `WebSocketAuthHeader` `ChannelManager` `SystemNotificationConsumer` `ConsistentHash` | 握手鉴权 + Channel 管理直接用；消费订单事件推送；一致性哈希/在线路由表解决多实例下“消息找不到连接”问题 |

---

## 4. 技术替换点

| 替换/新增 | 旧位置 | 新落点 | 说明 |
|---|---|---|---|
| Kafka → **RocketMQ 5.x** | UserService、RealTimeService、OfflineDataService、RedPacketService | order（事务消息：下单/取消事件；延迟消息：超时取消）、payment（支付成功事件）、product-inventory（消费：确认/释放库存）、realtime（消费：推送） | 建议**全量替换**，不保留 Kafka，避免维护两套 MQ。旧 Kafka 用法全部随 IM 业务删除，无需迁移数据 |
| ShedLock → **XXL-Job** | UserService、RedPacketService（均为“Redis ZSET 时间轮 + 每秒扫描”） | order（超时未支付兜底扫描）、payment（对账任务）、product-inventory（预占超时释放兜底） | 旧的两处用法随业务删除。订单超时**主路径**用 RocketMQ 延迟消息，XXL-Job 只做兜底和对账 |
| **Nacos** | 已用 Discovery | 所有服务：Discovery + **Config**（新增配置中心，Sentinel 规则也可持久化到 Nacos） | 现在各服务配置写死在 yml 里 |
| **Sentinel** | 无（RedPacket 用的是 Resilience4j） | gateway（入口限流）、order（下单接口限流/降级）、所有 Feign 调用（熔断降级） | 替代 Resilience4j |
| **Flowable 7.x** | 无 | user-tenant（引擎所在地：商家入驻审批）；product-inventory 作为发起方（商品上架审批） | 已定：引擎只放 user-tenant，结果经 MQ 回写 |
| **Elasticsearch 8.x** | 无 | order（订单多条件查询）；数据由 Canal 从 MySQL binlog 同步 | Canal 目标从 Redis 改为 ES |
| Resilience4j | RedPacketService | 删除 | Sentinel 替代 |
| 阿里云短信 SDK、commons-email | UserService | 删除短信 SDK（未见使用场景）；邮件保留 Spring Mail 即可 | 减少无用依赖 |
| pgvector → **ES 8 `dense_vector`** | AiService | ai | 复用已引入的 ES，不新增组件；kNN 查询内直接带 `tenant_id` 过滤 |

---

## 5. 风险与注意点

1. **根 pom 把依赖“强塞”给所有模块**：根 `<dependencies>` 里有 web、MySQL、Redis、JWT、Nacos、**LangChain4j**，导致 Gateway（WebFlux）也带着 `spring-boot-starter-web`，只能靠 `web-application-type: reactive` 硬压。A2 应改为根 pom 只做 `dependencyManagement`，各服务按需引。
2. **Common 与 AI 耦合**：`GlobalExceptionHandler` 引用 `InputGuardrailException`，这是 LangChain4j 被放进根 pom 的原因。拆掉后 common 才能干净。
3. **版本组合存疑**：Boot 3.5.11（CLAUDE.md 写的是 3.5.9）+ Spring Cloud 2025.0.0 + Spring Cloud Alibaba `2022.0.0.0-RC1`（RC 版，对应的是 Boot 3.0 时代）。A2 必须按 SCA 官方版本对照表重新选型；hutool 5.7.17、minio 8.2.1 也偏旧。**我不确定 SCA 最新哪个版本对应 Boot 3.5，请以官方 wiki 的版本说明为准。**
4. **身份透传缺失**：网关校验完 token 不往下游传身份，下游（如 `UserController.logout`）自己再解析 JWT。多租户必须改成“网关注入、下游信任网关头”，并清掉客户端伪造的头；`/internal` 接口要确保不被网关路由出去。
5. **安全债**：JWT 密钥硬编码在 `CommonConstant`；密码 MD5 + 固定盐；`LogInterceptor` 打印全部入参（含明文密码）。
6. **ID 生成冲突**：两套雪花工具都有 workerId 问题（见 2.2），订单号直接依赖它，A2/B 组必须先修。
7. **MQ 消费可靠性差**：Kafka 自动提交 offset、消费方法 catch 全部异常只打日志、`store-topic` 消费直接 insert 无幂等、生产失败只 `System.err`。订单链路不能照抄：要消费幂等、失败重试 + 死信、生产端可靠投递。
8. **实时推送多实例问题**：`ChannelManager` 是单机内存；推送消费者所有实例同一个 groupId，消息只会被一个实例消费，但用户连接可能在另一个实例上 → 推送丢失。迁移时要选“按用户路由到节点”或“广播消费 + 本机过滤”。
9. **Canal 同步可靠性**：重连时 `subscribe()` 不带 filter；写 Redis 失败被吞掉后仍然 ack → 数据丢失。同步 ES 时要做到“失败不 ack”，并用订单号 + 版本号做幂等 upsert。
10. **AI 越权面**：`RagTool` 允许模型写知识库、`EmailTool` 能对外发邮件、MCP 能联网，均是越权/外泄入口；向量检索目前无租户过滤；`chat-summary.txt` 文件实际名为 `chat-summary`（无后缀），摘要接口运行时会找不到 prompt。
11. **几乎没有测试**：仓库只有 `contextLoads` 级别测试（总计 79 行），没有可复用的测试资产，关键链路测试需从零写。

---

## 6. 决定记录（2026-11-16 拍板）

| # | 问题 | 决定 | 理由 | 代价 / 注意 |
|---|---|---|---|---|
| 1 | 8 个服务划分 | **接受**，不合并不拆分 | — | — |
| 2 | product 与 inventory | **合并**为 product-inventory | 库存挂在 SKU 上，“建 SKU + 初始化库存”需在同一本地事务；拆分主要为应对热点独立扩容，demo 规模不需要 | 包结构分 `product` / `inventory` 子域，接口不互相穿透，保留以后拆的可能 |
| 3 | order 与 payment | **分开** | 订单与支付单 1:N、生命周期不同；payment 要处理外部回调、验签、对账；两者间的最终一致性是学习重点 | 多一次跨服务链路，需要幂等 + 对账兜底 |
| 4 | Flowable 落点 | **user-tenant** | 审批人（平台运营、商家员工）和角色都在 user-tenant，候选人/组可直接映射；入驻审批回写租户状态是本地事务，减少远程开销 | 商品上架审批跨服务：product 用 Feign 发起，结果经 MQ 回写。须保证①可靠投递（事务消息/本地消息表 + 查询补偿）②消费幂等（`WHERE status = 待审核` CAS）③防乱序（消息带流程实例 ID） |
| 5 | AI 向量库 | **ES 8 `dense_vector`**，不用 pgvector | ES 已为订单查询引入，不新增表外组件；kNN 查询内可直接做 `tenant_id` 过滤，租户隔离在检索层完成 | 订单查询与 AI 共用集群，资源互相影响；HNSW 吃内存。**待核实**：LangChain4j 1.1 的 `langchain4j-elasticsearch` 与 ES 8 客户端的兼容性及 metadata filter 支持（A2 查官方文档） |
| 6 | Canal → ES 同步组件 | 放 **order** 内部 | 只同步订单表，不值得单独一个进程 | 同步线程故障会和 order 服务同生共死；后续量大可再拆 |
| 7 | Kafka | **全量替换为 RocketMQ** | 只维护一套 MQ；旧 Kafka 用法全部随 IM 业务删除 | — |

### 未决的事项（转交后续任务）
- Spring Cloud Alibaba 与 Boot 3.5.x 的版本组合 → A2 按官方版本对照表确定
- 实时推送多实例方案（按用户路由 vs 广播消费） → 做 realtime 任务时设计
