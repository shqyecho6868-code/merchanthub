package com.merchanthub.product;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * product-inventory 集成测试基类（C1）：租户插件 fail-closed，测试线程直接调 service 时默认用 SYSTEM 身份。
 * 自己开线程的用例（并发预占）要在子线程里自己设置上下文 —— ThreadLocal 不会跟着传过去，这正是生产里线程池要注意的事。
 */
public abstract class ProductItSupport extends IntegrationTestBase {

    private TenantContext.Scope tenantScope;

    /**
     * 走 MockMvc 的用例覆盖成 false：MockMvc 在测试线程上执行请求，测试线程上已有的 SYSTEM 身份会让
     * "没带身份头的请求"看起来像有身份，401 / 403 的用例就测不准了
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
