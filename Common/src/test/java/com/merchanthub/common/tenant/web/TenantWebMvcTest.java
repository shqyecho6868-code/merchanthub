package com.merchanthub.common.tenant.web;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.GlobalExceptionHandler;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Both interceptors + the global exception handler wired together (no Spring context): RBAC 403, missing identity 401, bad JSON 400 */
class TenantWebMvcTest {

    @RestController
    static class DemoController {
        @GetMapping("/demo/{id}")
        @RequireRole({Role.MERCHANT_ADMIN, Role.MERCHANT_STAFF})
        public BaseResponse<String> view(@PathVariable long id) {
            return ResultUtils.success("tenant=" + TenantContext.tenantId());
        }

        @PutMapping("/demo/{id}/price")
        @RequireRole(Role.MERCHANT_ADMIN)
        public BaseResponse<String> changePrice(@PathVariable long id, @RequestBody Map<String, Long> body) {
            return ResultUtils.success("ok");
        }

        @PostMapping("/demo/open")
        public BaseResponse<String> open(@RequestBody Map<String, Long> body) {
            return ResultUtils.success("ok");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new TenantContextInterceptor(), new RoleCheckInterceptor())
            .build();

    @AfterEach
    void noLeak() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Staff can read; the Controller sees the merchant from the request headers")
    void staffCanView() throws Exception {
        mvc.perform(get("/demo/1").header("X-Tenant-Id", "7").header("X-User-Id", "2002").header("X-Role", "MERCHANT_STAFF"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("tenant=7"));
    }

    @Test
    @DisplayName("Staff calling an admin endpoint → HTTP 403; admin → 200")
    void staffCallsAdminApi_forbidden() throws Exception {
        String body = "{\"price\": 100}";
        mvc.perform(put("/demo/1/price").header("X-Tenant-Id", "7").header("X-User-Id", "2002").header("X-Role", "MERCHANT_STAFF")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        mvc.perform(put("/demo/1/price").header("X-Tenant-Id", "7").header("X-User-Id", "2001").header("X-Role", "MERCHANT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Buyer calling a merchant endpoint → 403; no identity → 401")
    void buyerForbidden_anonymousUnauthorized() throws Exception {
        mvc.perform(get("/demo/1").header("X-User-Id", "50001").header("X-Role", "BUYER")).andExpect(status().isForbidden());
        mvc.perform(get("/demo/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bad JSON → HTTP 400 (was 500 before)")
    void badJson_400() throws Exception {
        mvc.perform(post("/demo/open").contentType(MediaType.APPLICATION_JSON).content("{\"price\": "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("Wrong path variable type → 400")
    void badPathVariable_400() throws Exception {
        mvc.perform(get("/demo/abc").header("X-Tenant-Id", "7").header("X-Role", "MERCHANT_ADMIN"))
                .andExpect(status().isBadRequest());
    }
}
