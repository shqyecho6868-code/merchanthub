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
 * user-tenant 集成测试基类：真实 MySQL（业务表 + Flowable ACT_* 表）、Redis、RocketMQ。
 * 所有子类共用同一组 Spring 配置 → 共用一个缓存的上下文（一个 Flowable 引擎），不会出现两个引擎抢同一批表。
 * <p>
 * 走 MockMvc 的请求自己带身份头（模拟网关注入），测试线程上不预设任何 TenantContext：
 * 否则"没带头的请求"看起来也像有身份，401 / 403 / 跨租户的用例就测不准了。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class UserItSupport extends IntegrationTestBase {

    /** 测试专用密钥（32 字节、补齐的 Base64）。dev 配置里是 ${JWT_SECRET}，这里用优先级更高的动态属性覆盖 */
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

    /** 模拟网关注入的身份头 */
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
     * 每个用例从干净状态开始：业务表清空 + 引擎里还在跑的流程实例全部删除。
     * <p>
     * 只清业务表不够（P32）：上一个测试类留下的待办还在 ACT_RU_TASK 里。CI（Linux）上测试类的执行顺序和 macOS 不同，
     * ListingApprovalIT 先跑时，它留下的"商家 7 初审"待办让 MerchantOnboardingIT 的"商家 7 管理员待办为空"断言失败。
     * 用引擎 API 删而不是 TRUNCATE ACT_* 表：引擎表之间有外键和缓存，直接删表会让引擎状态不一致。历史表保留，不影响断言（都按实例 ID 查）
     */
    protected void cleanBusinessTables() {
        runtimeService.createProcessInstanceQuery().list()
                .forEach(pi -> runtimeService.deleteProcessInstance(pi.getId(), "测试清理"));
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
