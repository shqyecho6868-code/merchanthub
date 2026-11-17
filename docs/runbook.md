# 运维手册（Runbook）

> A2+ 产出。只写“怎么操作、出问题怎么查”；版本选择的理由见 `env-setup.md` 和 `decisions.md`。
> 新增中间件（ES、Canal、XXL-Job、Sentinel……）的任务，完成时要在本文件补对应条目。

## 1. 一条命令启动全部中间件

前提：Docker Desktop 已启动；`.env` 已从 `.env.example` 复制并填好密码。

```bash
docker compose up -d
```

启动后确认（约 30 秒内全部就绪）：

```bash
docker compose ps -a
```

期望：`mh-mysql`、`mh-redis`、`mh-nacos`、`mh-namesrv`、`mh-broker`、`mh-rmq-dashboard` 都是 `Up`；`broker-init` 显示 `Exited (0)`（一次性任务，正常）。

| 操作 | 命令 |
|---|---|
| 看某个服务日志 | `docker compose logs -f broker` |
| 重启单个服务 | `docker compose restart nacos` |
| 停止（保留数据） | `docker compose down` |
| 停止并**清空所有数据** | `docker compose down -v` ⚠️ 不可恢复 |

## 2. 控制台与连接信息

| 组件 | 地址 | 账号 / 密码 |
|---|---|---|
| Nacos 控制台 | http://127.0.0.1:8080 | `nacos` / `.env` 的 `NACOS_PASSWORD` |
| Nacos 服务端（客户端连这个） | `127.0.0.1:8848`（gRPC 9848） | 同上 |
| RocketMQ Dashboard | http://127.0.0.1:8082 | 无 |
| RocketMQ NameServer | `127.0.0.1:9876` | 无 |
| MySQL | `127.0.0.1:3306`，库 `merchanthub` | `root` / `.env` 的 `MYSQL_ROOT_PASSWORD` |
| Redis | `127.0.0.1:6379` | 无用户名 / `.env` 的 `REDIS_PASSWORD` |

快速连通性检查：

```bash
source .env
docker exec mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "select version();"
docker exec mh-redis redis-cli -a "$REDIS_PASSWORD" ping            # 期望 PONG
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080       # 期望 200
docker exec mh-broker sh mqadmin clusterList -n namesrv:9876         # 期望看到 broker-a
```

## 3. 构建与测试

| 目的 | 命令 | 需要 Docker |
|---|---|---|
| 只跑单元测试（`*Test`） | `./mvnw test` | 否 |
| 单元 + 集成测试（`*IT`，Testcontainers）+ 覆盖率 | `./mvnw verify` | 是 |
| 只跑某个模块 | `./mvnw -pl Common verify` | 视情况 |
| 跳过集成测试 | `./mvnw verify -DskipITs` | 否 |
| 临时打开 70% 覆盖率门禁 | `./mvnw verify -Djacoco.check.skip=false` | 是 |

- 覆盖率报告：`<模块>/target/site/jacoco/index.html`
- 测试报告：`<模块>/target/surefire-reports/`（单测）、`<模块>/target/failsafe-reports/`（集成测试）
- 集成测试用 Testcontainers **自己起一套临时容器**，不使用 compose 的中间件，两者可以同时运行、互不影响
- 命令行先切到 JDK 17：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`（本机默认 `java` 是 26）

## 4. CI

- 配置：`.github/workflows/ci.yml`，每次 push / PR 执行 `./mvnw -B verify`
- 查看：GitHub 仓库 → Actions → 某次运行 → Summary 页有各模块行覆盖率表；`jacoco-reports` 附件是 HTML 报告；失败时额外上传 `test-reports`
- README 顶部的徽章显示 main 分支最近一次构建状态

## 5. 常见故障与排查

先看哪里：**容器起不来 → `docker compose logs <服务>`；测试失败 → `target/failsafe-reports/*.txt` 里的 `Caused by`；CI 失败 → Actions 日志 + `test-reports` 附件。**

| 现象 | 先查 | 常见原因 → 处理 |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop 是否启动 | 打开 Docker Desktop，等鲸鱼图标稳定再跑 `./mvnw verify` |
| `mh-broker` 一直 Restarting，日志末尾是 NPE | `docker compose logs broker` 往前翻 | 存储目录无写权限：确认 `broker-init` 是 `Exited (0)`；否则 `docker compose up -d broker-init` 后重启 broker（P7） |
| 发消息超时 / `No route info` | `mqadmin clusterList` 看 Broker 注册的地址 | 地址是 172.x → `brokerIP1` 配置没生效；宿主机程序连不上 `host.docker.internal` → `/etc/hosts` 没加（env-setup 第 5 节） |
| RocketMQ 客户端 `send request to <...> failed`，NameServer 端口是通的 | `dependency:tree -Dincludes=com.alibaba.fastjson2` | `fastjson` 与 `fastjson2` 版本错位 → 根 pom 的 `fastjson2.version` 必须与 SCA 管理的 fastjson 一致（P12） |
| Nacos 控制台能打开，但服务注册超时 | `docker compose ps` 看端口映射 | 9848（gRPC）没映射 |
| Nacos 登录失败 / 提示初始化 | `.env` 的 `NACOS_PASSWORD` | 3.x 首次启动需初始化管理员密码；`down -v` 清过数据卷后要重新初始化 |
| RocketMQ Dashboard 打不开 | `docker compose logs dashboard` 找 `Tomcat started on port` | 2.1.0 容器内端口是 8082（P8） |
| `container name "/mh-xxx" is already in use` | `docker ps -a` | 有其他 compose 项目的同名旧容器，确认无用后 `docker rm -f`（P9） |
| `port is already allocated` | `lsof -i :<端口>` | 本机已有 MySQL/Redis 在跑，停掉本机服务或改映射端口 |
| 集成测试卡在 `Pulling image` 很久 | 网络 | 第一次要拉镜像，之后有缓存；可先 `docker pull mysql:8.0 redis:7.4 apache/rocketmq:5.3.1` |
| 编译报 `release version 17 not supported` 或 JaCoCo 报不支持的 class 版本 | `java -version` | 用了 JDK 26，先切到 17 |
| 覆盖率是 0 但测试明明跑了 | 模块 pom 是否自定义了 `argLine` | 自定义时必须写成 `@{argLine} -Xxx`，否则 JaCoCo agent 被覆盖 |
| `.env` 新增变量后读不到 / 拼到上一行 | `cat -A .env` 看换行 | 文件末尾缺换行，`>>` 追加时串行（P10） |
| IDEA 显示推送成功但新仓库没有提交 | `git config branch.main.remote` | 跟踪的 remote 是 `legacy`，改回 `origin`（P11） |
| 命令行 `git push` 报 403 | — | 命令行凭据没有 `merchanthub` 仓库权限，在 IDEA 里推送（P11） |
