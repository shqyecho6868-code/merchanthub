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
