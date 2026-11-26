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
 * Integration test: order tenant isolation + RBAC (MockMvc -> interceptor -> Controller -> tenant plugin -> real MySQL).
 * Scenario: buyer 1001 placed order O7 at merchant 7 and order O8 at merchant 8.
 * <p>
 * Conclusion: when merchant 7's staff queries O8's order number, it returns "Order not found" -- neither Controller nor Service writes tenant_id;
 * the tenant plugin rewrote the SQL to {@code WHERE order_no = O8 AND tenant_id = 7}.
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
        return false;   // the cases test requests "without identity", so no identity may be preset on the test thread
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
        // And vice versa
        mvc.perform(as(get("/orders/" + o7), OTHER_TENANT, 3001, "MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** Can a forged tenantId request header bypass it? -- downstreams trust the header, so the gateway must sanitize it (see AuthorizeFilterTest). This proves "the header decides" */
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
        // Another buyer queries O7's order number: filtered by buyer_id, not found
        mvc.perform(as(get("/orders/mine/" + o7), null, 1002, "BUYER")).andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void rbac() throws Exception {
        // Buyer calls a merchant back-office API -> 403
        mvc.perform(as(get("/orders/" + o7), null, BUYER, "BUYER")).andExpect(status().isForbidden());
        // Merchant staff calls the order placement API (buyers only) -> 403
        mvc.perform(as(post("/orders"), TENANT, 2002, "MERCHANT_STAFF").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":7,\"items\":[{\"skuId\":101,\"quantity\":1}]}")).andExpect(status().isForbidden());
        // No identity -> 401
        mvc.perform(get("/orders/" + o7)).andExpect(status().isUnauthorized());
        // Contradictory identity headers (merchant role without tenantId) -> 401
        mvc.perform(as(get("/orders/" + o7), null, 2002, "MERCHANT_STAFF")).andExpect(status().isUnauthorized());
    }

    @Test
    void buyerPlacesOrderViaHttp_badJson400() throws Exception {
        mvc.perform(as(post("/orders"), null, BUYER, "BUYER").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":7,\"items\":[{\"skuId\":101,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(10));
        mvc.perform(as(post("/orders"), null, BUYER, "BUYER").contentType(MediaType.APPLICATION_JSON).content("{\"items\":["))
                .andExpect(status().isBadRequest());
    }

    /** fail-closed: a thread with no context at all (e.g. a thread pool someone forgot to set up) querying orders -> error, rather than returning every merchant's orders */
    @Test
    void noContextThread_failsClosed() {
        CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> ordersMapper.selectCount(null));
        assertThatThrownBy(f::join).hasRootCauseInstanceOf(TenantContextMissingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(2);
    }
}
