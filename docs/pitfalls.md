# 踩坑记录

> 格式：现象 → 定位 → 根因 → 解决

## 旧代码隐患（A1 读代码发现，尚未修复）

### P1｜雪花 ID workerId 实际恒为 0
- **现象**：多实例部署时 RealTimeService 生成的 messageId 可能重复
- **定位**：`SnowflakeDynamicUtil` 在 `static` 字段上加 `@Value("${snowflake.workerId}")`
- **根因**：Spring 不会给静态字段注入值，类加载时 `IdUtil.getSnowflake(0, 0)` 已执行；Common 的 `SnowflakeUtil` 则把 workerId 写死为 1。两者在多实例下都会冲突
- **解决**：common 中统一 ID 生成器，启动时按实例分配 workerId（A2/B 组实施）

### P2｜多实例推送丢失
- **现象**：用户连在实例 1，消息被实例 2 消费 → 找不到 Channel，推送丢失
- **定位**：所有 RealTimeService 实例使用同一 consumer groupId；`ChannelManager` 是单机内存 Map
- **根因**：集群消费模式下一条消息只投给组内一个实例，但连接状态不共享
- **解决**：广播消费 + 本机过滤，或 Redis 路由表（`userId → 实例`，心跳续期 + TTL）定向推送（realtime 任务设计）

### P3｜系统通知遇到离线用户时 NPE
- **定位**：`SystemNotificationConsumer` 没判断 `channel` 是否为 null 就调用了 `writeAndFlush`
- **解决**：迁移到 realtime 时补判空；推送只是通知，真实数据以订单查询为准

### P4｜Canal 重连后丢失表过滤，异常被吞后仍 ack
- **定位**：`CanalClient.reconnectCanal()` 调用的是无参 `subscribe()`；`storeMessageToRedis` 捕获异常后正常返回，外层随即 `ack`
- **根因**：无参 subscribe 会使用服务端配置的过滤规则；处理失败对外层不可见，导致这批 binlog 被确认消费，数据丢失
- **解决**：同步 ES 时失败必须向上抛出并 rollback；重连时带 filter；ES 写入用订单号 + 版本号做幂等 upsert

### P5｜会话摘要 prompt 文件找不到
- **定位**：`AiChat.chatSummary` 引用 `system-prompt/chat-summary.txt`，实际文件名是 `chat-summary`（没有后缀）
- **解决**：AI 改造时随功能删除或修正

### P6｜登录密码打进日志
- **定位**：Common 的 `LogInterceptor` 用 `StringUtils.join(args)` 打印 controller 的全部入参
- **解决**：common 改造时对敏感字段脱敏，并加入 MDC traceId/tenantId

## A2 环境搭建

### P7｜RocketMQ Broker 反复重启，日志只有 NPE
- **现象**：`mh-broker` 一直处于 Restarting，日志末尾是 `ScheduleMessageService.configFilePath` 的 NullPointerException
- **定位**：NPE 出现在 `BrokerController.shutdown` 调用链里，说明是初始化失败后关闭时的副作用；用临时容器测试，发现存储目录属于 root，uid 3000 的 rocketmq 用户 `touch` 失败
- **根因**：新建的命名卷默认属于 root，镜像以非 root 用户运行
- **解决**：加一个一次性服务 `broker-init` 来 `chown`，Broker 通过 `depends_on: service_completed_successfully` 等它完成

### P8｜Dashboard 端口映射后访问不到
- **现象**：`127.0.0.1:8082` 连接失败
- **根因**：Dashboard 2.1.0 在容器内监听的是 8082，不是旧版本的 8080
- **解决**：映射改为 `8082:8082`。教训：端口以镜像日志里的 `Tomcat started on port` 为准

### P9｜修改 compose 项目名后容器名冲突
- **现象**：`The container name "/mh-mysql" is already in use`
- **根因**：加了 `name: merchanthub` 后项目名变了，旧项目 `shoppingmall` 下的同名容器还在
- **解决**：确认旧容器只有一个空库后，删除旧容器和旧卷

### P10｜追加 `.env` 时串行
- **现象**：新增的 `REDIS_PASSWORD` 被拼接到了 MySQL 密码那一行
- **根因**：原文件最后一行没有换行符，`>>` 追加时接在同一行后面
- **解决**：拆成两行；以后追加前先检查文件末尾是否有换行符

### P11｜改了 remote 名字后，提交被推到了旧仓库
- **现象**：IDEA 显示推送成功，但新仓库 `merchanthub` 是空的，提交出现在旧仓库 `InfiniteChat`
- **根因**：`git remote rename origin legacy` 会同步修改 `branch.main.remote`，main 分支的跟踪目标跟着变成了 `legacy/main`；IDEA 默认推送到当前跟踪的 remote
- **解决**：`git config branch.main.remote origin`（或首次推送时用 `git push -u origin main`），推送前确认弹窗写的是 `main → origin : main`
- **另**：命令行和 IDEA 用的是不同的 GitHub 凭据，命令行凭据没有新仓库的权限（403），目前只能在 IDEA 里推送

## A2+ 测试框架与 CI

### P12｜RocketMQ 客户端 `send request to <localhost:xxxx> failed`，NameServer 端口却是通的
- **现象**：Testcontainers 集成测试里，生产者第一步“向 NameServer 查路由”就失败；容器日志显示 NameServer 和 Broker 都 `boot success`
- **定位**：
  1. 起一个探针容器，从宿主机用同一份 classpath 直连，同样失败 → 与 Testcontainers 无关，问题在客户端
  2. “send request failed”是请求在**编码/写出阶段**就失败（不是超时），怀疑序列化依赖
  3. `dependency:build-classpath` 发现：`com.alibaba:fastjson`（2.x 兼容层）和 `fastjson2-extension` 是 2.0.58（SCA BOM 管理），`fastjson2` 核心却是 2.0.31（根 pom 为对齐 canal 锁定）
  4. 只把 classpath 里的 fastjson2 换成 2.0.58 → `SEND_OK`
- **根因**：fastjson 兼容层内部直接调用 fastjson2 核心，同一家族的 jar 版本错位 → 运行时找不到方法，RocketMQ remoting 编码请求头失败
- **解决**：根 pom `fastjson2.version` 改为 2.0.58，与 SCA 管理的 fastjson 对齐
- **教训**：“同一家族多个 jar”（fastjson/fastjson2、netty-*、jackson-*）必须同版本；只锁其中一个比不锁更危险

### P13｜根 pom 的 `netty.version` 与 Spring Boot 属性重名（隐患，已修）
- **现象**：排查 P12 时发现全项目所有 Netty 模块都是 4.2.0.Final，而 Spring Boot 3.5 管理的是 4.1.x
- **根因**：根 pom 定义了 `<netty.version>`，恰好与 Boot 父 pom 的同名属性重名，Maven 子 pom 属性覆盖父 pom 属性 → 不只是 RealTimeService 的 netty-all，Gateway 的 Reactor Netty、Lettuce、RocketMQ remoting 一起被升到 4.2
- **说明**：P12 的失败在修掉这个之后仍然存在，所以它不是 P12 的直接原因；但 Reactor Netty / RocketMQ 是基于 4.1 编译的，混用是潜在的运行时风险。RealTimeService 只用了 4.1 的 `NioEventLoopGroup`，退回 4.1 无影响
- **解决**：删除该属性及 netty-all 的版本声明，交给 Boot 管理
- **教训**：在继承 spring-boot-starter-parent 的 pom 里自定义 `xxx.version` 前，先查 Boot 是否已有同名属性（`help:evaluate -Dexpression=xxx.version`）

## B1 下单 + 库存预占

### P14｜Testcontainers 报 `Previous attempts to find a Docker environment failed`
- **现象**：单测全过，所有 `*IT` 在启动容器时报错
- **定位**：`docker info` → `Cannot connect to the Docker daemon`
- **根因**：Docker Desktop 没启动；`/var/run/docker.sock` 是指向 `~/.docker/run/docker.sock` 的软链接，Docker Desktop 不运行时目标文件不存在
- **解决**：启动 Docker Desktop，等状态变为 Running 再跑 `./mvnw verify`

### P15｜命令行编译报 Lombok 生成的方法找不到（`cannot find symbol: method builder()` / `variable log`）
- **现象**：`./mvnw verify` 在 Common 报大量 Lombok 相关编译错误
- **根因**：本机默认 `java` 是 26，Boot 3.5 管理的 Lombok 不支持；runbook 已有记录，但新开终端容易忘
- **解决**：先 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

### P16｜复制的长命令在终端里被拆成几条，报 `command not found: verify`
- **现象**：`./mvnw ... verify -Dxxx` 报 `No goals have been specified`，接着 `zsh: command not found: verify`
- **根因**：从聊天窗口复制时，长命令按显示宽度被折成多行，终端把每一行当成独立命令执行，`./mvnw -pl X` 没带 goal 就先执行了
- **解决**：长命令写成脚本，或者确认粘贴后是单独一行再回车
