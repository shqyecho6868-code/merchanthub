package com.merchanthub.common.tenant;

import com.merchanthub.common.tenant.feign.TenantFeignInterceptor;
import com.merchanthub.common.tenant.mq.TenantMessages;
import feign.RequestTemplate;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨服务的两种透传：Feign（请求头）和 RocketMQ（消息属性） */
class TenantFeignAndMqTest {

    private final TenantFeignInterceptor feign = new TenantFeignInterceptor();

    @AfterEach
    void noLeak() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Feign：当前身份写成请求头；客户端模板里原有的同名头被覆盖")
    void feignCopiesContext() {
        RequestTemplate t = new RequestTemplate();
        t.header("X-Tenant-Id", "999");
        TenantContext.runAs(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN), () -> feign.apply(t));
        assertThat(t.headers().get("X-Tenant-Id")).containsExactly("7");
        assertThat(t.headers().get("X-User-Id")).containsExactly("2001");
        assertThat(t.headers().get("X-Role")).containsExactly("MERCHANT_ADMIN");
    }

    @Test
    @DisplayName("Feign：买家没有 tenantId 就不带这个头；没有上下文什么都不带")
    void feignBuyerAndNone() {
        RequestTemplate buyer = new RequestTemplate();
        TenantContext.runAs(new TenantInfo(null, 50001L, Role.BUYER), () -> feign.apply(buyer));
        assertThat(buyer.headers()).doesNotContainKey("X-Tenant-Id").containsKey("X-Role");

        RequestTemplate none = new RequestTemplate();
        none.header("X-Role", "SYSTEM");
        feign.apply(none);
        assertThat(none.headers()).doesNotContainKeys("X-Role", "X-Tenant-Id", "X-User-Id");
    }

    @Test
    @DisplayName("MQ：发送时带上 tenantId，消费时恢复成该商家，消费完还原（消费线程复用也不串）")
    void mqStampAndConsume() throws Exception {
        MessageExt m = new MessageExt();
        TenantMessages.stamp(m, 8L);

        ExecutorService consumerThread = Executors.newSingleThreadExecutor();   // 模拟 RocketMQ 复用的消费线程
        try {
            Long seenInside = consumerThread.submit(() -> TenantMessages.consumeAs(m, TenantContext::tenantId)).get();
            assertThat(seenInside).isEqualTo(8L);
            assertThat(consumerThread.submit(TenantContext::get).get()).as("消费完线程是干净的").isNull();
        } finally {
            consumerThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("MQ：消息没带 tenantId → 拒绝处理（不知道是哪个商家的，不能跨租户处理）")
    void mqWithoutTenantRejected() {
        Message m = new Message("T", "tag", new byte[0]);
        assertThatThrownBy(() -> TenantMessages.consumeAs(m, () -> { }))
                .isInstanceOf(TenantContextMissingException.class);
        assertThat(TenantMessages.tenantIdOf(m)).isNull();
    }
}
