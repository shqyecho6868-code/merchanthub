package com.merchanthub.common.tenant.mq;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantHeaders;
import com.merchanthub.common.tenant.TenantInfo;
import org.apache.rocketmq.common.message.Message;

import java.util.function.Supplier;

/**
 * RocketMQ 的租户透传（C1）：消息没有请求头，发送时把 tenantId 写进 user property，消费时读出来恢复上下文。
 * <p>
 * 为什么发送方显式传 tenantId、不从 ThreadLocal 取：order 的发件箱是扫描线程（SYSTEM，跨租户）在发，
 * 线程上下文不是这条消息的商家；消息属于哪个商家应该由消息自己（outbox 行、支付单）决定。
 */
public final class TenantMessages {

    private TenantMessages() {
    }

    public static Message stamp(Message msg, long tenantId) {
        msg.putUserProperty(TenantHeaders.MQ_TENANT_ID, String.valueOf(tenantId));
        return msg;
    }

    /** 消息里的商家 ID；没有返回 null */
    public static Long tenantIdOf(Message msg) {
        String v = msg.getUserProperty(TenantHeaders.MQ_TENANT_ID);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 以消息所属商家的身份执行消费逻辑，执行完还原（RocketMQ 消费线程是复用的，必须还原）。
     * 消息没带 tenantId → 抛 {@link TenantContextMissingException}：不知道是哪个商家的消息，宁可重投到死信，也不能跨租户处理。
     */
    public static <T> T consumeAs(Message msg, Supplier<T> action) {
        Long tenantId = tenantIdOf(msg);
        if (tenantId == null) {
            throw new TenantContextMissingException("消息缺少 " + TenantHeaders.MQ_TENANT_ID + " 属性 keys=" + msg.getKeys());
        }
        return TenantContext.callAs(TenantInfo.systemOf(tenantId), action);
    }

    public static void consumeAs(Message msg, Runnable action) {
        consumeAs(msg, () -> {
            action.run();
            return null;
        });
    }
}
