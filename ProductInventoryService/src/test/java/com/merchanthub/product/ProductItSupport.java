package com.merchanthub.product;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for product-inventory integration tests (C1): the tenant plugin is fail-closed, so when the test thread calls a service directly it uses a SYSTEM identity by default.
 * Tests that start their own threads (concurrent reservation) must set the context in each child thread — ThreadLocal doesn't propagate, which is exactly what thread pools in production must watch out for.
 */
public abstract class ProductItSupport extends IntegrationTestBase {

    private TenantContext.Scope tenantScope;

    /**
     * Tests that go through MockMvc override this to false: MockMvc runs the request on the test thread, and the SYSTEM identity already on it would make
     * "a request without identity headers" look authenticated, so the 401 / 403 cases wouldn't be tested accurately
     */
    protected boolean defaultSystemContext() {
        return true;
    }

    @BeforeEach
    void openSystemTenantScope() {
        tenantScope = defaultSystemContext() ? TenantContext.open(TenantInfo.system()) : null;
    }

    @AfterEach
    void closeTenantScope() {
        if (tenantScope != null) {
            tenantScope.close();
        }
    }
}
