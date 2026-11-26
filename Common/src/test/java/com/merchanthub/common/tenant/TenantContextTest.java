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
        assertThat(TenantContext.get()).as("No context may remain on the thread after each test").isNull();
    }

    @Test
    @DisplayName("Readable after open; fully cleared after close, including tenantId in the MDC")
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
    @DisplayName("Nested: a system job processes one record as merchant 8, then returns to the system identity rather than 'nothing'")
    void nestedRestoresPrevious() {
        TenantContext.runAsSystem(() -> {
            assertThat(TenantContext.get().role()).isEqualTo(Role.SYSTEM);
            TenantContext.runAs(TenantInfo.systemOf(8L), () -> assertThat(TenantContext.tenantId()).isEqualTo(8L));
            assertThat(TenantContext.get()).isEqualTo(TenantInfo.system());
        });
    }

    @Test
    @DisplayName("Restored even when business code throws (try-with-resources)")
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
    @DisplayName("Thread pool reuse: no cleanup → next task leaks into the previous merchant; cleanup → clean")
    void threadPoolReuse() throws Exception {
        // Counter-example: the task opens but never closes (simulating forgotten cleanup)
        ExecutorService leaky = Executors.newSingleThreadExecutor();
        try {
            leaky.submit(() -> TenantContext.open(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN))).get();
            Long seenByNextTask = leaky.submit(TenantContext::tenantId).get();
            assertThat(seenByNextTask).as("The next task on the same thread (which has no identity) sees merchant 7 -- cross-tenant leak").isEqualTo(7L);
        } finally {
            leaky.shutdownNow();   // destroying the thread drops the leaked ThreadLocal too; in production threads are reused long-term and don't go away on their own
        }

        // Correct style: runAs / try-with-resources, always restored when the task ends
        ExecutorService clean = Executors.newSingleThreadExecutor();
        try {
            clean.submit(() -> TenantContext.runAs(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN), () -> { })).get();
            assertThat(clean.submit(TenantContext::tenantId).get()).as("The next task sees a clean thread").isNull();
        } finally {
            clean.shutdownNow();
        }
    }

    @Test
    @DisplayName("requireTenantId: no merchant context → throw, don't guess")
    void requireTenantId() {
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(TenantContextMissingException.class);
        TenantContext.runAs(new TenantInfo(null, 50001L, Role.BUYER), () ->
                assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(TenantContextMissingException.class));
    }

    @Test
    @DisplayName("Merchant roles must carry a tenantId")
    void merchantRequiresTenant() {
        assertThatThrownBy(() -> new TenantInfo(null, 1L, Role.MERCHANT_ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }
}
