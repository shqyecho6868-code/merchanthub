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
