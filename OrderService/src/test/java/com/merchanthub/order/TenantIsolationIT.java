package com.merchanthub.order;

import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.order.mapper.OrdersMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 集成测试：订单的租户隔离 + RBAC（MockMvc → 拦截器 → Controller → 租户插件 → 真实 MySQL）。
 * 场景：买家 1001 在商家 7 下了订单 O7，在商家 8 下了订单 O8。
 * <p>
 * 结论：商家 7 的员工拿 O8 的订单号来查，返回"订单不存在"—— Controller 和 Service 里都没写 tenant_id，
 * 是租户插件把 SQL 改成了 {@code WHERE order_no = O8 AND tenant_id = 7}。
 */
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
@AutoConfigureMockMvc
class TenantIsolationIT extends OrderItSupport {

    private static final long OTHER_TENANT = 8L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private OrdersMapper ordersMapper;

    private long o7;
    private long o8;

    @Override
    protected boolean defaultSystemContext() {
        return false;   // 用例要测"没带身份"的请求，测试线程上不能预置身份
    }

    @BeforeEach
    void placeTwoOrders() {
        catalog.put(201L, sku(201, OTHER_TENANT, 500, "{}"));
        TenantInfo buyer = new TenantInfo(null, BUYER, Role.BUYER);
        o7 = TenantContext.callAs(buyer, () -> Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo()));
        o8 = TenantContext.callAs(buyer, () -> Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 201, 1)).getOrderNo()));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Long tenant, long user, String role) {
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        return b.header("X-User-Id", user).header("X-Role", role);
    }

    @Test
    void merchant7_cannotSeeMerchant8sOrder() throws Exception {
        mvc.perform(as(get("/orders/" + o7), TENANT, 2002, "MERCHANT_STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(String.valueOf(o7)))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT));
        mvc.perform(as(get("/orders/" + o8), TENANT, 2002, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").doesNotExist());
        // 反过来也一样
        mvc.perform(as(get("/orders/" + o7), OTHER_TENANT, 3001, "MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** 请求头里伪造 tenantId 能不能绕过？—— 下游信任请求头，所以必须靠网关清洗（见 AuthorizeFilterTest）。这里证明的是"头说了算" */
    @Test
    void contextComesFromHeaders() throws Exception {
        mvc.perform(as(get("/orders/" + o8), OTHER_TENANT, 3001, "MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.data.tenantId").value(OTHER_TENANT));
    }

    @Test
    void platformAdmin_seesAllTenants() throws Exception {
        mvc.perform(as(get("/orders/" + o8), null, 1, "PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.data.tenantId").value(OTHER_TENANT));
    }

    @Test
    void buyer_seesOwnOrdersAcrossTenants_butNotOthers() throws Exception {
        mvc.perform(as(get("/orders/mine/" + o7), null, BUYER, "BUYER")).andExpect(jsonPath("$.data.tenantId").value(TENANT));
        mvc.perform(as(get("/orders/mine/" + o8), null, BUYER, "BUYER")).andExpect(jsonPath("$.data.tenantId").value(OTHER_TENANT));
        // 另一个买家拿 O7 的订单号来查：按 buyer_id 过滤，查不到
        mvc.perform(as(get("/orders/mine/" + o7), null, 1002, "BUYER")).andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void rbac() throws Exception {
        // 买家调商家后台接口 → 403
        mvc.perform(as(get("/orders/" + o7), null, BUYER, "BUYER")).andExpect(status().isForbidden());
        // 商家员工调下单接口（买家专用）→ 403
        mvc.perform(as(post("/orders"), TENANT, 2002, "MERCHANT_STAFF").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"skuId\":101,\"quantity\":1}]}")).andExpect(status().isForbidden());
        // 没有身份 → 401
        mvc.perform(get("/orders/" + o7)).andExpect(status().isUnauthorized());
        // 身份头自相矛盾（商家角色没有 tenantId）→ 401
        mvc.perform(as(get("/orders/" + o7), null, 2002, "MERCHANT_STAFF")).andExpect(status().isUnauthorized());
    }

    @Test
    void buyerPlacesOrderViaHttp_badJson400() throws Exception {
        mvc.perform(as(post("/orders"), null, BUYER, "BUYER").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuId\":101,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(10));
        mvc.perform(as(post("/orders"), null, BUYER, "BUYER").contentType(MediaType.APPLICATION_JSON).content("{\"items\":["))
                .andExpect(status().isBadRequest());
    }

    /** fail-closed：一个没有任何上下文的线程（比如忘了设置的线程池）去查订单 → 报错，而不是返回全部商家的订单 */
    @Test
    void noContextThread_failsClosed() {
        CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> ordersMapper.selectCount(null));
        assertThatThrownBy(f::join).hasRootCauseInstanceOf(TenantContextMissingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(2);
    }
}
