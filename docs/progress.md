# 进度

| 任务 | 状态 | 日期 | 产出 | 过关 |
|---|---|---|---|---|
| B2 RocketMQ 事务消息 + 本地消息表 | 完成 | 2026-11-20 | 方案一事务消息（半消息 + 本地事务 + 回查，两道防线）；方案二本地消息表（同事务写入 + afterCommit 快路径 + 扫描补偿，抢占租约 + 指数退避 + 上限）；`event-mode` 开关二选一（默认 OUTBOX）；消费端按 event_id 幂等（去重记录与业务同事务）；order 服务引入 Flyway（V1 基线 / V2 outbox / V3 消费去重 + 商家通知）；🧑 kill 实验（第三次成功，前两次暴露回查上限与 P18）；MQ 不可用实验（自动化 + 真停 Broker）；`docs/notes/B2-事务消息与本地消息表.md` | 稳（Q1、Q4 及两道追问直接答对；Q2 经提示补全 kill 时状态与"同组新实例查共享库"；Q3 经两轮提示讲完整。🔍 审代码：claim 改 status 的 bug 找到且后果说对；逐段 6 问全对，第 4、6 段第二问提示后答对）；CI ✅ |
| B1 下单 + 库存预占 | 完成 | 2026-11-18 | 雪花 workerId Redis 租约（修 P1）；新模块 ProductInventoryService / OrderService / TestSupport；8 张表 DDL + 预占记录表；下单接口（Feign 查 SKU → 预占 → 本地事务落单）；多 SKU 有序条件更新预占（幂等）；单测 + 并发 IT；破坏性实验（去掉 `available >= ?` → 超卖 90 件）；`docs/notes/B1-下单与库存预占.md` | 基本稳（第 1 题"行锁 + 代码主动抛异常"经讲解后复述正确；第 2、3 题及追问稳。🔍 审代码：`rows < 0` 位置找到，后果经讲解后理解；逐段 7 问中 5 问答对，快速路径讲解后答对）；CI ✅ |
| A3 业务建模 | 完成 | 2026-11-17 | `docs/data-model.md`（8 张表 + ER 图 + 订单状态机 + 设计取舍）、`docs/notes/A3-业务建模.md` | 稳（状态 CAS、快照分两层两题经提示后答对） |
| A2+ runbook + 测试框架 + CI | 完成 | 2026-11-16 | `docs/runbook.md`、JUnit5/Mockito/Testcontainers/JaCoCo、`.github/workflows/ci.yml`、README 徽章、Maven Wrapper；修复 fastjson2 版本错位（P12）与 Netty 版本覆盖（P13） | 本任务不考 |
| A2 环境搭建 | 完成 | 2026-11-16 | 包名 `com.merchanthub`、删除 RedPacketService、依赖重构（SCA 2025.0.0.0）、`docker-compose.yml`、`docs/env-setup.md` | 本任务不考 |
| A1 旧项目分析与迁移地图 | 完成 | 2026-11-16 | `docs/migration-map.md` | 稳（按“先讲后考”规则重判：原判不稳的两点都没有先讲过，其中推送路由表属于 realtime 任务内容，改为预告） |

## 遗留问题

- [B·订单] 已支付后取消 / 超时未发货不支持（需退款，范围外），以后做退款时加“退款中”状态（见 D17）
- [B] 后续任务落 DDL / 改字段时继续回写 data-model.md 第 6 节变更记录
- [B] Canal 1.1.7 现在运行在 fastjson2 2.0.58 上（P12），编译通过但未实测；OfflineDataService 改造为 Canal→ES 时验证

- ~~宿主机 `/etc/hosts` 加 `host.docker.internal`~~ → B2 已加
- [B] 时区已定为数据库存 UTC（B1）；旧 IM 代码里仍有写死的 `Asia/Shanghai`（`FormatDateUtil`、`TimeTool`、Offline 模块）及旧服务 JDBC URL 的 `America/Los_Angeles`，随旧代码删除或改造时统一
- [B] 旧代码仍引用 Kafka、MinIO（bucket `infinitechat`）、pgvector，均未拉起；服务暂不能完整启动（A2 不要求）
- [B] `CommonConstant.TOKEN_SECRET_KEY` 仍为硬编码旧字符串，改造鉴权时移到配置
- [B·AI] 核实 LangChain4j 1.1 的 `langchain4j-elasticsearch` 对 ES 8 与 metadata filter 的支持
- ~~雪花 workerId 冲突（P1）~~ → B1 用 Redis 租约领号解决（D20）；时钟回拨仍只抛异常不发号（已知限制）
- [B] 实时推送多实例方案（广播消费 vs 按用户路由）待 realtime 任务设计
- [B] `OfflineDataService` 包名临时为 `com.merchanthub.offline`，Canal 拆到 order 后整体删除
- [B3·对账] **有订单、无事件**：事务消息方案下生产者组整体宕机超过回查上限（周期 × 15 次），半消息被丢弃（B2 第一次 kill 实验实测）；本地消息表方案下记录被标成失败（status=2）。B3 对账任务找出这两类并补发
- [B3] 本地消息表扫描暂用 Spring `@Scheduled`（多实例靠抢占不重复发），引入 XXL-Job 时迁移；已发送记录的归档 / 清理未做
- [B·realtime] 演示消费者 `MerchantNoticeConsumer` 与 `merchant_order_notice` 暂放 order 服务（方案 A），realtime 任务时迁走
- [B·product] product-inventory 仍用 `schema.sql`，第一次改表时切 Flyway（D26）
- [B2/B3·补偿] **悬空预占**：inventory 预占成功、order 落单失败或 Feign 超时（order 不知道是否占上）时，库存被占着却没有订单。B1 只记日志；B2/B3 按 `inventory_reservation` 找出"有预占、无订单"的记录释放（D21）
- [B2/B3] 支付确认扣减 / 超时释放：预占记录 `WHERE status = 10` CAS，释放 SQL 带 `reserved >= n`
- [B·下单] 防重复提交（买家连点两次 → 两个订单）：确认页下发下单 token，提交时按 token 去重；B1 未做
- [B·网关] 网关鉴权后注入 `X-User-Id`（先删客户端同名头）；`/internal/**` 不对外转发。B1 下单接口直接读 `X-User-Id`
- [B] Feign 预占目前不重试（预占已幂等，可安全加重试）；Sentinel 熔断降级待接入
- [B] 可考虑给 `inventory.available` 加 `CHECK (available >= 0)` 作为第二道防线（B1 为做破坏性实验故意不加）
- ~~表结构变更靠 `CREATE TABLE IF NOT EXISTS`~~ → B2 order 服务已引入 Flyway（D26）
- ~~SCA 版本不匹配~~、~~根 pom 强塞依赖~~、~~包名占位符~~ → A2 已解决
