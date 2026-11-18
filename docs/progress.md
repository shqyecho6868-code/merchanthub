# 进度

| 任务 | 状态 | 日期 | 产出 | 过关 |
|---|---|---|---|---|
| B1 下单 + 库存预占 | 完成 | 2026-11-18 | 雪花 workerId Redis 租约（修 P1）；新模块 ProductInventoryService / OrderService / TestSupport；8 张表 DDL + 预占记录表；下单接口（Feign 查 SKU → 预占 → 本地事务落单）；多 SKU 有序条件更新预占（幂等）；单测 + 并发 IT；破坏性实验（去掉 `available >= ?` → 超卖 90 件）；`docs/notes/B1-下单与库存预占.md` | 基本稳（第 1 题"行锁 + 代码主动抛异常"经讲解后复述正确；第 2、3 题及追问稳） |
| A3 业务建模 | 完成 | 2026-11-17 | `docs/data-model.md`（8 张表 + ER 图 + 订单状态机 + 设计取舍）、`docs/notes/A3-业务建模.md` | 稳（状态 CAS、快照分两层两题经提示后答对） |
| A2+ runbook + 测试框架 + CI | 完成 | 2026-11-16 | `docs/runbook.md`、JUnit5/Mockito/Testcontainers/JaCoCo、`.github/workflows/ci.yml`、README 徽章、Maven Wrapper；修复 fastjson2 版本错位（P12）与 Netty 版本覆盖（P13） | 本任务不考 |
| A2 环境搭建 | 完成 | 2026-11-16 | 包名 `com.merchanthub`、删除 RedPacketService、依赖重构（SCA 2025.0.0.0）、`docker-compose.yml`、`docs/env-setup.md` | 本任务不考 |
| A1 旧项目分析与迁移地图 | 完成 | 2026-11-16 | `docs/migration-map.md` | 稳（按“先讲后考”规则重判：原判不稳的两点都没有先讲过，其中推送路由表属于 realtime 任务内容，改为预告） |

## 遗留问题

- [B·订单] 已支付后取消 / 超时未发货不支持（需退款，范围外），以后做退款时加“退款中”状态（见 D17）
- [B] 后续任务落 DDL / 改字段时继续回写 data-model.md 第 6 节变更记录
- [B] Canal 1.1.7 现在运行在 fastjson2 2.0.58 上（P12），编译通过但未实测；OfflineDataService 改造为 Canal→ES 时验证

- [B·RocketMQ] 宿主机 `/etc/hosts` 需加 `127.0.0.1 host.docker.internal`（需 sudo），写 RocketMQ 代码前做，见 env-setup 第 5 节
- [B] 时区已定为数据库存 UTC（B1）；旧 IM 代码里仍有写死的 `Asia/Shanghai`（`FormatDateUtil`、`TimeTool`、Offline 模块）及旧服务 JDBC URL 的 `America/Los_Angeles`，随旧代码删除或改造时统一
- [B] 旧代码仍引用 Kafka、MinIO（bucket `infinitechat`）、pgvector，均未拉起；服务暂不能完整启动（A2 不要求）
- [B] `CommonConstant.TOKEN_SECRET_KEY` 仍为硬编码旧字符串，改造鉴权时移到配置
- [B·AI] 核实 LangChain4j 1.1 的 `langchain4j-elasticsearch` 对 ES 8 与 metadata filter 的支持
- ~~雪花 workerId 冲突（P1）~~ → B1 用 Redis 租约领号解决（D20）；时钟回拨仍只抛异常不发号（已知限制）
- [B] 实时推送多实例方案（广播消费 vs 按用户路由）待 realtime 任务设计
- [B] `OfflineDataService` 包名临时为 `com.merchanthub.offline`，Canal 拆到 order 后整体删除
- [B2/B3·补偿] **悬空预占**：inventory 预占成功、order 落单失败或 Feign 超时（order 不知道是否占上）时，库存被占着却没有订单。B1 只记日志；B2/B3 按 `inventory_reservation` 找出"有预占、无订单"的记录释放（D21）
- [B2/B3] 支付确认扣减 / 超时释放：预占记录 `WHERE status = 10` CAS，释放 SQL 带 `reserved >= n`
- [B·下单] 防重复提交（买家连点两次 → 两个订单）：确认页下发下单 token，提交时按 token 去重；B1 未做
- [B·网关] 网关鉴权后注入 `X-User-Id`（先删客户端同名头）；`/internal/**` 不对外转发。B1 下单接口直接读 `X-User-Id`
- [B] Feign 预占目前不重试（预占已幂等，可安全加重试）；Sentinel 熔断降级待接入
- [B] 可考虑给 `inventory.available` 加 `CHECK (available >= 0)` 作为第二道防线（B1 为做破坏性实验故意不加）
- [B] 表结构变更目前靠 `CREATE TABLE IF NOT EXISTS`，改已有表时需评估引入 Flyway（技术栈表外，需先确认）
- ~~SCA 版本不匹配~~、~~根 pom 强塞依赖~~、~~包名占位符~~ → A2 已解决
