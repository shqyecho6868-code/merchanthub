# 决策记录

> 格式：选了什么 / 没选什么 / 为什么 / 代价。详细背景见 `migration-map.md` 第 6 节。

## D1｜服务划分为 8 个（A1，2026-11-16）
- **选**：gateway、user-tenant、product-inventory、order、payment、realtime、ai、common
- **没选**：单独的 workflow 服务、单独的 search/sync 服务
- **为什么**：demo 规模下，每多一个服务就多一套部署和一条远程链路；审批和搜索各只服务一两个场景
- **代价**：user-tenant、order 职责偏重；以后规模变大再拆

## D2｜product 与 inventory 合并（A1）
- **选**：合并为 product-inventory，包内分 `product` / `inventory` 子域
- **没选**：拆成两个服务
- **为什么**：库存挂在 SKU 上，“建 SKU + 初始化库存”需在同一本地事务中完成；拆分主要是为了独立扩容、隔离热点，本项目不存在
- **代价**：库存成为热点时无法单独扩容；靠子域边界保留以后拆的可能

## D3｜order 与 payment 分开（A1）
- **选**：分开
- **没选**：合并成交易服务
- **为什么**：订单与支付单是 1:N（多次支付尝试）；生命周期和意图不同；payment 要处理外部回调、验签、对账；两者间的最终一致性是学习重点
- **代价**：多一条跨服务链路，存在“支付成功但订单未更新”的一致性窗口，需要 MQ 幂等消费 + 对账兜底

## D4｜Flowable 引擎放 user-tenant（A1）
- **选**：引擎只放 user-tenant；product 用 Feign 发起上架审批，结果经 MQ 回写
- **没选**：独立审批服务（两类审批都要远程，成本最高）；放 product（入驻审批归属错误、反向依赖）；两个服务各嵌一个引擎（两套引擎表，混乱）
- **为什么**：审批人和角色都在 user-tenant，候选人/组可直接映射；入驻审批回写租户状态是本地事务
- **代价**：上架审批跨服务，须保证可靠投递、消费幂等（CAS）、防乱序（带流程实例 ID）

## D5｜AI 向量库用 ES 8 `dense_vector`，不用 pgvector（A1）
- **选**：ES 8
- **没选**：pgvector（技术栈表外组件）、Redis Stack
- **为什么**：ES 已为订单查询引入，不新增组件；kNN 查询内可直接做 `tenant_id` 过滤，租户隔离在检索层完成
- **代价**：与订单查询共用集群、资源互相影响；HNSW 索引吃内存；LangChain4j 集成的兼容性待 A2 核实

## D6｜Kafka 全量替换为 RocketMQ；ShedLock 由 XXL-Job 替代（A1）
- **选**：只保留 RocketMQ；超时取消主路径用 RocketMQ 延迟消息，XXL-Job 做兜底扫描和对账
- **没选**：Kafka + RocketMQ 并存；沿用“Redis ZSET 时间轮 + ShedLock 每秒扫描”
- **为什么**：只维护一套 MQ；RocketMQ 原生支持事务消息和延迟消息，正好对应下单一致性和超时取消；旧 Kafka/ShedLock 用法全部随 IM 业务删除，无迁移成本
- **代价**：要重新学 RocketMQ 的消费模型和事务消息回查

## D7｜Canal → ES 同步组件放 order 内部（A1）
- **选**：放在 order 服务内
- **没选**：单独的 sync 进程
- **为什么**：只同步订单表
- **代价**：同步线程和 order 服务同生共死
