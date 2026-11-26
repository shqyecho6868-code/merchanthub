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
 * Integration test: product tenant isolation + RBAC. Goes through the full Spring MVC chain (interceptor → Controller → tenant plugin → real MySQL).
 * Scenario: merchant 7 has product P7 / SKU S7, merchant 8 has product P8 / SKU S8.
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
        p7 = insertProduct(7, "Merchant 7 T-shirt");
        p8 = insertProduct(8, "Merchant 8 hoodie");
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
                .andExpect(jsonPath("$.data.name").value("Merchant 7 T-shirt"))
                .andExpect(jsonPath("$.data.skus[0].skuId").value(s7));
        // Query with merchant 8's product ID: the SQL gets tenant_id = 7 appended, so nothing is found
        mvc.perform(as(get("/products/" + p8), 7L, 2001, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void platformAdmin_seesAnyTenant() throws Exception {
        mvc.perform(as(get("/products/" + p8), null, 1, "PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.data.tenantId").value(8));
    }

    /** Staff calling an admin API → 403, price unchanged */
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

        // Changing merchant 8's SKU: UPDATE ... WHERE id = s8 AND tenant_id = 7 affects 0 rows
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
