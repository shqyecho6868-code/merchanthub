# A2+ 测试框架与 CI：本项目特有的调整与原因

> 通用的 JUnit / Maven / GitHub Actions 用法不在这里讲。本任务不考核。

## 1. 两层测试，命名决定谁来跑

- `*Test` → surefire，`./mvnw test`，不连任何中间件，秒级
- `*IT` → failsafe，`./mvnw verify`，Testcontainers 临时起 MySQL 8.0 / Redis 7.4 / RocketMQ 5.3.1（与 compose 同版本）
- 没 Docker 时 IT 会**直接失败而不是跳过**：故意的，跳过会让“测试通过”变成假绿

## 2. RocketMQ 没有现成的 Testcontainers 模块，自己封装

和 A2 笔记第 3 条是同一个问题：客户端连的是 Broker **自己注册**的地址。Testcontainers 默认随机映射端口，Broker 不知道这个随机端口。
做法：宿主机先找一个空闲端口 P → Broker 在容器内也监听 P → 固定映射 P:P → `brokerIP1` 设为 Docker 主机地址。代码见 `Common/src/test/.../MiddlewareContainersIT.java` 的 `RocketMqContainer`。

## 3. 搭框架时顺带挖出的两个依赖问题（pitfalls P12、P13）

- **fastjson2 被锁在 2.0.31**，而 SCA 管理的 fastjson 兼容层是 2.0.58 → RocketMQ 客户端一发请求就失败。A2 没写 RocketMQ 代码，所以当时没暴露——这正是“先有集成测试”的价值
- **根 pom 的 `netty.version` 和 Spring Boot 属性重名**，把全项目 Netty 都覆盖成了 4.2。已删，交回 Boot 管理（4.1.x）

## 4. 覆盖率：合并统计、门禁按模块开

- 单测和集成测试写同一个 `jacoco.exec`，报告是两者合并的结果
- 门禁默认关；order / inventory / payment 在自己 pom 里加 `<jacoco.check.skip>false</jacoco.check.skip>` 即启用 70%
- 模块里如果要自定义 `argLine`，必须写 `@{argLine} ...`，否则 JaCoCo agent 被顶掉，覆盖率变 0

## 5. CI

- `.github/workflows/ci.yml`：push / PR 都跑 `./mvnw -B verify`；Summary 页有各模块覆盖率表；HTML 报告作为附件下载
- 用 Maven Wrapper，保证 CI 和本机 Maven 版本一致；wrapper 的下载地址从阿里云镜像改回了 Maven Central
