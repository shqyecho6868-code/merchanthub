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
     * Why clearing is mandatory (thread pool reuse): a pool with a single thread runs a merchant 7 task and then a task with no identity,
     * both on <b>the same thread</b>. If the first task doesn't clean up, the second one thinks it is merchant 7.
     */
    @Test
    @DisplayName("线程池复用：不清理 → 下一个任务串到上一个商家；清理 → 干净")
    void threadPoolReuse() throws Exception {
        // Counter-example: the task opens but never closes (simulating forgotten cleanup)
        ExecutorService leaky = Executors.newSingleThreadExecutor();
        try {
            leaky.submit(() -> TenantContext.open(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN))).get();
            Long seenByNextTask = leaky.submit(TenantContext::tenantId).get();
            assertThat(seenByNextTask).as("同一个线程上的下一个任务（本来没身份）读到了商家 7 —— 串租户").isEqualTo(7L);
        } finally {
            leaky.shutdownNow();   // destroying the thread drops the leaked ThreadLocal too; in production threads are reused long-term and don't go away on their own
        }

        // Correct style: runAs / try-with-resources, always restored when the task ends
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
