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

/** 两个拦截器 + 全局异常处理串起来跑（不起 Spring 容器）：RBAC 的 403、身份缺失的 401、坏 JSON 的 400 */
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
    @DisplayName("员工能看，Controller 里读到的是请求头里的商家")
    void staffCanView() throws Exception {
        mvc.perform(get("/demo/1").header("X-Tenant-Id", "7").header("X-User-Id", "2002").header("X-Role", "MERCHANT_STAFF"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("tenant=7"));
    }

    @Test
    @DisplayName("员工调管理员接口 → HTTP 403；管理员 → 200")
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
    @DisplayName("买家调商家接口 → 403；没有身份 → 401")
    void buyerForbidden_anonymousUnauthorized() throws Exception {
        mvc.perform(get("/demo/1").header("X-User-Id", "50001").header("X-Role", "BUYER")).andExpect(status().isForbidden());
        mvc.perform(get("/demo/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("坏 JSON → HTTP 400（之前是 500，P23）")
    void badJson_400() throws Exception {
        mvc.perform(post("/demo/open").contentType(MediaType.APPLICATION_JSON).content("{\"price\": "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("路径参数类型不对 → 400")
    void badPathVariable_400() throws Exception {
        mvc.perform(get("/demo/abc").header("X-Tenant-Id", "7").header("X-Role", "MERCHANT_ADMIN"))
                .andExpect(status().isBadRequest());
    }
}
