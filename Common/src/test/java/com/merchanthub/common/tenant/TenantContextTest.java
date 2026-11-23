package com.merchanthub.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void noLeak() {
        assertThat(TenantContext.get()).as("每个用例结束后线程上都不应残留上下文").isNull();
    }

    @Test
    @DisplayName("open 之后能读到；close 之后彻底清掉，MDC 里的 tenantId 也清掉")
    void openAndClose() {
        try (var ignored = TenantContext.open(new TenantInfo(7L, 2001L, Role.MERCHANT_STAFF))) {
            assertThat(TenantContext.tenantId()).isEqualTo(7L);
            assertThat(TenantContext.get().role()).isEqualTo(Role.MERCHANT_STAFF);
            assertThat(MDC.get("tenantId")).isEqualTo("7");
        }
        assertThat(TenantContext.get()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    @DisplayName("嵌套：系统任务里按商家 8 处理一条记录，处理完回到系统身份，而不是变成'什么都没有'")
    void nestedRestoresPrevious() {
        TenantContext.runAsSystem(() -> {
            assertThat(TenantContext.get().role()).isEqualTo(Role.SYSTEM);
            TenantContext.runAs(TenantInfo.systemOf(8L), () -> assertThat(TenantContext.tenantId()).isEqualTo(8L));
            assertThat(TenantContext.get()).isEqualTo(TenantInfo.system());
        });
    }

    @Test
    @DisplayName("业务代码抛异常也会还原（try-with-resources）")
    void restoredOnException() {
        assertThatThrownBy(() -> TenantContext.runAs(TenantInfo.systemOf(7L), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.get()).isNull();
    }

    /**
     * 为什么必须清理（线程池复用）：只有 1 个线程的池子，先后执行商家 7 和"没带身份"的两个任务，
     * 两个任务跑在<b>同一个线程</b>上。第一个任务如果不清理，第二个任务就会以为自己是商家 7。
     */
    @Test
    @DisplayName("线程池复用：不清理 → 下一个任务串到上一个商家；清理 → 干净")
    void threadPoolReuse() throws Exception {
        // 反例：任务里 open 了却不 close（模拟忘了清理）
        ExecutorService leaky = Executors.newSingleThreadExecutor();
        try {
            leaky.submit(() -> TenantContext.open(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN))).get();
            Long seenByNextTask = leaky.submit(TenantContext::tenantId).get();
            assertThat(seenByNextTask).as("同一个线程上的下一个任务（本来没身份）读到了商家 7 —— 串租户").isEqualTo(7L);
        } finally {
            leaky.shutdownNow();   // 线程销毁，泄漏的 ThreadLocal 跟着没了；生产里线程是长期复用的，不会自己消失
        }

        // 正确写法：runAs / try-with-resources，任务结束必还原
        ExecutorService clean = Executors.newSingleThreadExecutor();
        try {
            clean.submit(() -> TenantContext.runAs(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN), () -> { })).get();
            assertThat(clean.submit(TenantContext::tenantId).get()).as("下一个任务看到的是干净的线程").isNull();
        } finally {
            clean.shutdownNow();
        }
    }

    @Test
    @DisplayName("requireTenantId：没有商家上下文就报错，不猜")
    void requireTenantId() {
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(TenantContextMissingException.class);
        TenantContext.runAs(new TenantInfo(null, 50001L, Role.BUYER), () ->
                assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(TenantContextMissingException.class));
    }

    @Test
    @DisplayName("商家角色必须带 tenantId")
    void merchantRequiresTenant() {
        assertThatThrownBy(() -> new TenantInfo(null, 1L, Role.MERCHANT_ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }
}
