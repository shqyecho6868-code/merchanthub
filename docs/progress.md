# 进度

| 任务 | 状态 | 日期 | 产出 | 过关 |
|---|---|---|---|---|
| A1 旧项目分析与迁移地图 | 完成 | 2026-11-16 | `docs/migration-map.md` | 不稳（跨服务状态变更主路径；推送路由表存放与失效） |

## 遗留问题

- [A2] Spring Cloud Alibaba 当前为 `2022.0.0.0-RC1`，与 Boot 3.5.11 / Spring Cloud 2025.0.0 很可能不匹配，需按官方版本对照表重选
- [A2] 根 pom 的 `<dependencies>` 强塞依赖给所有模块（含 LangChain4j、starter-web 进 Gateway），改为只做 `dependencyManagement`
- [A2] 核实 LangChain4j 1.1 的 `langchain4j-elasticsearch` 对 ES 8 客户端与 metadata filter 的支持
- [A2/B] 雪花 ID workerId 问题（见 pitfalls）需在订单号落地前修复
- [B] 实时推送多实例方案（广播消费 vs 按用户路由）待 realtime 任务设计
- CLAUDE.md 第 6 节包名仍为占位符，A2 改包名前需确定
