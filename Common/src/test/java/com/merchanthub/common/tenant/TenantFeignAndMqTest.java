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

/** Two ways identity crosses services: Feign (request headers) and RocketMQ (message properties) */
class TenantFeignAndMqTest {

    private final TenantFeignInterceptor feign = new TenantFeignInterceptor();

    @AfterEach
    void noLeak() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Feign: current identity written as headers; same-named headers already on the client template are overwritten")
    void feignCopiesContext() {
        RequestTemplate t = new RequestTemplate();
        t.header("X-Tenant-Id", "999");
        TenantContext.runAs(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN), () -> feign.apply(t));
        assertThat(t.headers().get("X-Tenant-Id")).containsExactly("7");
        assertThat(t.headers().get("X-User-Id")).containsExactly("2001");
        assertThat(t.headers().get("X-Role")).containsExactly("MERCHANT_ADMIN");
    }

    @Test
    @DisplayName("Feign: buyer without tenantId omits that header; no context sends nothing")
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
    @DisplayName("MQ: tenantId attached on send, restored as that merchant on consume, and reset afterwards (no leak even on a reused consumer thread)")
    void mqStampAndConsume() throws Exception {
        MessageExt m = new MessageExt();
        TenantMessages.stamp(m, 8L);

        ExecutorService consumerThread = Executors.newSingleThreadExecutor();   // simulates RocketMQ's reused consumer thread
        try {
            Long seenInside = consumerThread.submit(() -> TenantMessages.consumeAs(m, TenantContext::tenantId)).get();
            assertThat(seenInside).isEqualTo(8L);
            assertThat(consumerThread.submit(TenantContext::get).get()).as("The thread is clean after consuming").isNull();
        } finally {
            consumerThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("MQ: message without tenantId → rejected (unknown merchant, must not process across tenants)")
    void mqWithoutTenantRejected() {
        Message m = new Message("T", "tag", new byte[0]);
        assertThatThrownBy(() -> TenantMessages.consumeAs(m, () -> { }))
                .isInstanceOf(TenantContextMissingException.class);
        assertThat(TenantMessages.tenantIdOf(m)).isNull();
    }
}
