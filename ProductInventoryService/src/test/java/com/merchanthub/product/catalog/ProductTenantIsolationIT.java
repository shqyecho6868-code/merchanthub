package com.merchanthub.product.catalog;

import com.merchanthub.product.ProductItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 集成测试：商品的租户隔离 + RBAC。走完整的 Spring MVC 链路（拦截器 → Controller → 租户插件 → 真实 MySQL）。
 * 场景：商家 7 有商品 P7 / SKU S7，商家 8 有商品 P8 / SKU S8。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductTenantIsolationIT extends ProductItSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private long p7;
    private long p8;
    private long s7;
    private long s8;

    @Override
    protected boolean defaultSystemContext() {
        return false;
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE product");
        jdbc.execute("TRUNCATE TABLE sku");
        p7 = insertProduct(7, "商家7的T恤");
        p8 = insertProduct(8, "商家8的卫衣");
        s7 = insertSku(7, p7, "S7", 9900);
        s8 = insertSku(8, p8, "S8", 19900);
    }

    private long insertProduct(long tenant, String name) {
        jdbc.update("INSERT INTO product (tenant_id, name, status) VALUES (?, ?, 2)", tenant, name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertSku(long tenant, long productId, String code, long price) {
        jdbc.update("INSERT INTO sku (tenant_id, product_id, sku_code, spec_json, price, status) VALUES (?, ?, ?, '{}', ?, 1)",
                tenant, productId, code, price);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Long tenant, long user, String role) {
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        return b.header("X-User-Id", user).header("X-Role", role);
    }

    private long price(long skuId) {
        return jdbc.queryForObject("SELECT price FROM sku WHERE id = ?", Long.class, skuId);
    }

    @Test
    void merchant7_seesOwnProduct_notMerchant8s() throws Exception {
        mvc.perform(as(get("/products/" + p7), 7L, 2001, "MERCHANT_STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("商家7的T恤"))
                .andExpect(jsonPath("$.data.skus[0].skuId").value(s7));
        // 拿商家 8 的商品 ID 来查：SQL 被拼上 tenant_id = 7，查不到
        mvc.perform(as(get("/products/" + p8), 7L, 2001, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void platformAdmin_seesAnyTenant() throws Exception {
        mvc.perform(as(get("/products/" + p8), null, 1, "PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.data.tenantId").value(8));
    }

    /** 员工调管理员接口 → 403，价格不变 */
    @Test
    void staffCannotChangePrice_403() throws Exception {
        mvc.perform(as(put("/skus/" + s7 + "/price"), 7L, 2002, "MERCHANT_STAFF")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        assertThat(price(s7)).isEqualTo(9900);
    }

    @Test
    void buyerCannotSeeMerchantBackend_403() throws Exception {
        mvc.perform(as(get("/products/" + p7), null, 50001, "BUYER")).andExpect(status().isForbidden());
    }

    @Test
    void admin7_changesOwnPrice_butNotMerchant8s() throws Exception {
        mvc.perform(as(put("/skus/" + s7 + "/price"), 7L, 2001, "MERCHANT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 8800}"))
                .andExpect(status().isOk());
        assertThat(price(s7)).isEqualTo(8800);

        // 改商家 8 的 SKU：UPDATE ... WHERE id = s8 AND tenant_id = 7 影响 0 行
        mvc.perform(as(put("/skus/" + s8 + "/price"), 7L, 2001, "MERCHANT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 1}"))
                .andExpect(jsonPath("$.code").value(40400));
        assertThat(price(s8)).isEqualTo(19900);
    }

    @Test
    void badJson_400() throws Exception {
        mvc.perform(as(put("/skus/" + s7 + "/price"), 7L, 2001, "MERCHANT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noIdentity_401() throws Exception {
        mvc.perform(get("/products/" + p7)).andExpect(status().isUnauthorized());
    }
}
