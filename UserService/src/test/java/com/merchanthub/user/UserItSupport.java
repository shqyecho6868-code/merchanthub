package com.merchanthub.user;

import com.merchanthub.testsupport.IntegrationTestBase;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for user-tenant integration tests: real MySQL (business tables + Flowable ACT_* tables), Redis, RocketMQ.
 * All subclasses share the same Spring config -> one cached context (one Flowable engine), so two engines never fight over the same tables.
 * <p>
 * Requests via MockMvc carry their own identity headers (simulating gateway injection); no TenantContext is preset on the test thread:
 * otherwise "requests without headers" would look authenticated too, and the 401 / 403 / cross-tenant cases couldn't be tested reliably.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class UserItSupport extends IntegrationTestBase {

    /** Test-only secret (32 bytes, padded Base64). The dev config uses ${JWT_SECRET}; overridden here with a higher-precedence dynamic property */
    public static final String TEST_JWT_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtZm9yLWl0LTEyMzQ1Ng==";

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("merchanthub.jwt.secret", () -> TEST_JWT_SECRET);
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected RuntimeService runtimeService;

    /** Simulates the identity headers injected by the gateway */
    protected static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Long tenant, long user, String role) {
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        return b.header("X-User-Id", user).header("X-Role", role);
    }

    protected static MockHttpServletRequestBuilder platformAdmin(MockHttpServletRequestBuilder b) {
        return as(b, null, 1, "PLATFORM_ADMIN");
    }

    protected long insertAccount(Long tenantId, String username, String password, String role) {
        jdbc.update("INSERT INTO account (tenant_id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                tenantId, username, passwordEncoder.encode(password), role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    protected void insertTenant(long id, String name, int status) {
        jdbc.update("INSERT INTO tenant (id, name, status) VALUES (?, ?, ?)", id, name, status);
    }

    /**
     * Each test starts from a clean state: business tables truncated + all process instances still running in the engine deleted.
     * <p>
     * Clearing only the business tables isn't enough: tasks left by the previous test class are still in ACT_RU_TASK. On CI (Linux) the test class order differs from macOS,
     * and when ListingApprovalIT ran first, its leftover "merchant 7 first review" task broke MerchantOnboardingIT's "merchant 7 admin has no tasks" assertion.
     * Delete via the engine API instead of TRUNCATE on ACT_* tables: engine tables have foreign keys and caches, so dropping rows directly leaves the engine inconsistent. History tables are kept; they don't affect assertions (all queried by instance ID)
     */
    protected void cleanBusinessTables() {
        runtimeService.createProcessInstanceQuery().list()
                .forEach(pi -> runtimeService.deleteProcessInstance(pi.getId(), "test cleanup"));
        for (String t : new String[]{"account", "tenant", "merchant_application", "listing_approval", "approval_outbox"}) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
    }

    protected int runtimeTasks(String pid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", Integer.class, pid);
    }

    protected int historicTasks(String pid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = ?", Integer.class, pid);
    }
}
