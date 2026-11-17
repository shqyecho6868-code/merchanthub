# 进度

| 任务 | 状态 | 日期 | 产出 | 过关 |
|---|---|---|---|---|
| A3 业务建模 | 完成 | 2026-11-17 | `docs/data-model.md`（8 张表 + ER 图 + 订单状态机 + 设计取舍）、`docs/notes/A3-业务建模.md` | 稳（状态 CAS、快照分两层两题经提示后答对） |
| A2+ runbook + 测试框架 + CI | 完成 | 2026-11-16 | `docs/runbook.md`、JUnit5/Mockito/Testcontainers/JaCoCo、`.github/workflows/ci.yml`、README 徽章、Maven Wrapper；修复 fastjson2 版本错位（P12）与 Netty 版本覆盖（P13） | 本任务不考 |
| A2 环境搭建 | 完成 | 2026-11-16 | 包名 `com.merchanthub`、删除 RedPacketService、依赖重构（SCA 2025.0.0.0）、`docker-compose.yml`、`docs/env-setup.md` | 本任务不考 |
| A1 旧项目分析与迁移地图 | 完成 | 2026-11-16 | `docs/migration-map.md` | 稳（按“先讲后考”规则重判：原判不稳的两点都没有先讲过，其中推送路由表属于 realtime 任务内容，改为预告） |

## 遗留问题

- [B·订单] 已支付后取消 / 超时未发货不支持（需退款，范围外），以后做退款时加“退款中”状态（见 D17）
- [B·库存] 库存预占记录表（按订单号记录预占数量，保证预占/释放/确认幂等）在 B 组库存任务设计
- [B] data-model.md 是设计稿，落 DDL 时字段如有调整要回写文档
- [B1] 示例 IT 放在 Common；B1 起各服务都要起容器时，考虑抽一个共享的测试基类（单例容器，多个测试类复用同一组容器，省启动时间）
- [B] Canal 1.1.7 现在运行在 fastjson2 2.0.58 上（P12），编译通过但未实测；OfflineDataService 改造为 Canal→ES 时验证

- [B·RocketMQ] 宿主机 `/etc/hosts` 需加 `127.0.0.1 host.docker.internal`（需 sudo），写 RocketMQ 代码前做，见 env-setup 第 5 节
- [A3/B] Java 代码中仍有写死的 `Asia/Shanghai`（`FormatDateUtil`、`TimeTool`、Offline 模块），时区策略写订单时统一定（建议库存 UTC）
- [B] 旧代码仍引用 Kafka、MinIO（bucket `infinitechat`）、pgvector，均未拉起；服务暂不能完整启动（A2 不要求）
- [B] `CommonConstant.TOKEN_SECRET_KEY` 仍为硬编码旧字符串，改造鉴权时移到配置
- [B·AI] 核实 LangChain4j 1.1 的 `langchain4j-elasticsearch` 对 ES 8 与 metadata filter 的支持
- [A2/B] 雪花 ID workerId 问题（见 pitfalls P1）需在订单号落地前修复
- [B] 实时推送多实例方案（广播消费 vs 按用户路由）待 realtime 任务设计
- [B] `OfflineDataService` 包名临时为 `com.merchanthub.offline`，Canal 拆到 order 后整体删除
- ~~SCA 版本不匹配~~、~~根 pom 强塞依赖~~、~~包名占位符~~ → A2 已解决
