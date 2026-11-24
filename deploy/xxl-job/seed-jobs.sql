-- E3：登记 MerchantHub 的执行器和任务（在官方建表脚本之后执行）。
-- 执行器 address_type = 0：各服务启动时自动注册地址，不用手填。
-- 任务路由 FIRST：每次只调第一台在线实例；阻塞策略 SERIAL_EXECUTION：上一次没跑完，下一次排队（同一任务不会并发跑）。
-- 对账类任务默认"停止"，演示时在控制台手动"执行一次"；演示完可以在控制台启动它们按 Cron 自动跑。
USE xxl_job;

INSERT INTO xxl_job_group (id, app_name, title, address_type, address_list, update_time) VALUES
    (10, 'order', 'order 订单', 0, NULL, NOW()),
    (11, 'payment', 'payment 支付', 0, NULL, NOW()),
    (12, 'product-inventory', 'product-inventory 商品库存', 0, NULL, NOW()),
    (13, 'user-tenant', 'user-tenant 租户审批', 0, NULL, NOW());

INSERT INTO xxl_job_info (id, job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
                          misfire_strategy, executor_route_strategy, executor_handler, executor_param,
                          executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source,
                          glue_remark, glue_updatetime, child_jobid, trigger_status) VALUES
    (100, 10, '订单本地消息表补偿投递', NOW(), NOW(), 'E3', '', 'CRON', '0/5 * * * * ?', 'DO_NOTHING', 'FIRST',
     'orderOutboxRelayJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1),
    (101, 10, '超时订单兜底关单', NOW(), NOW(), 'E3', '', 'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
     'orderTimeoutScanJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 1),
    (102, 10, '订单侧对账（支付异常标记待退款 / outbox 失败告警）', NOW(), NOW(), 'E3', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'orderReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (103, 10, '已发送消息归档', NOW(), NOW(), 'E3', '', 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST',
     'orderOutboxArchiveJob', '', 'SERIAL_EXECUTION', 600, 0, 'BEAN', '', '', NOW(), '', 1),
    (110, 11, '支付单 ↔ 订单对账', NOW(), NOW(), 'E3', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'paymentReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (120, 12, '库存预占 ↔ 订单对账', NOW(), NOW(), 'E3', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'reservationReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (121, 12, '上架审批补偿', NOW(), NOW(), 'E3', '', 'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
     'listingCompensateJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 1),
    (130, 13, '审批结果本地消息表补偿投递', NOW(), NOW(), 'E3', '', 'CRON', '0/5 * * * * ?', 'DO_NOTHING', 'FIRST',
     'approvalOutboxRelayJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1);
