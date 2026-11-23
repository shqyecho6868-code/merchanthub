package com.merchanthub.common.tenant;

/**
 * 访问业务表时拿不到租户上下文（fail-closed，C1 设计 Q3）。
 * 出现它说明某个入口忘了设置上下文（新加的 MQ 消费者、定时任务、线程池……），是代码缺陷：
 * 宁可报错，也不能不带 tenant_id 去查全表。
 */
public class TenantContextMissingException extends IllegalStateException {

    public TenantContextMissingException(String message) {
        super(message);
    }
}
