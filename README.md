# MerchantHub

[![CI](https://github.com/shqyecho6868-code/merchanthub/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/shqyecho6868-code/merchanthub/actions/workflows/ci.yml)

多商家（多租户）电商交易平台后端，学习项目。由分布式 IM 项目“千言 IM”改造而来。

- 技术栈：Spring Boot 3.5 · Java 17 · Spring Cloud Alibaba（Nacos）· RocketMQ 5 · MySQL 8 · Redis 7 · Netty · Elasticsearch 8 + Canal · Flowable 7 · XXL-Job · Sentinel
- 业务范围：租户（平台 → 商家 → 员工）、商品与 SKU、库存预占/确认/释放、订单状态机与交易快照、模拟支付、审批流、订单实时推送（多节点路由 + 离线补发）、订单搜索、对账补偿

## 快速开始

```bash
cp .env.example .env          # 填写密码
docker compose up -d          # 启动全部中间件
./mvnw test                   # 单元测试（不需要 Docker）
./mvnw verify                 # 单元测试 + Testcontainers 集成测试 + 覆盖率报告（需要 Docker）
```

需要本机装有 JDK 17 并登记到 `~/.m2/toolchains.xml`（Maven Toolchains 自动选用，默认 `java` 是别的版本也可以，见 `docs/env-setup.md`）。覆盖率报告在 `<模块>/target/site/jacoco/index.html`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/runbook.md](docs/runbook.md) | 运维手册：启动、控制台地址与账号、常见故障排查 |
| [docs/env-setup.md](docs/env-setup.md) | 版本、端口、账号 |
| [docs/architecture.md](docs/architecture.md) | 架构图与核心链路时序图 |
| [docs/decisions.md](docs/decisions.md) | 技术决策记录 |
| [docs/pitfalls.md](docs/pitfalls.md) | 踩坑记录 |
| [docs/progress.md](docs/progress.md) | 进度 |
