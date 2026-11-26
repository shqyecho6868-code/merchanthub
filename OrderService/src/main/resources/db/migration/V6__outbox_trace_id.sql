-- 链路追踪：发件箱记下写入时请求的 traceId，投递时放回消息属性，消费者日志能和下单请求串起来。
-- 发送在另一个线程（提交后线程池 / XXL-Job 扫描），那时请求的 MDC 早已清掉，只能随行落库（和 tenant_id 同理）。
-- 可空：V6 之前的老记录没有，投递时新生成一个
ALTER TABLE order_outbox ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '写入时请求的 traceId';

-- 归档表必须同步加，且同样加在最后一列：归档用 INSERT ... SELECT *，两张表列的个数和顺序必须完全一致
ALTER TABLE order_outbox_archive ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '写入时请求的 traceId';
