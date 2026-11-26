package com.merchanthub.user.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.user.UserItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant onboarding approval (D1): submit -> platform admin review -> if approved, create tenant + open merchant admin account; reject -> applicant revises and resubmits (same instance) / withdraws.
 * Scenario: buyer 9001 applies to open "Shop Nine", reviewed by platform admin 1.
 */
class MerchantOnboardingIT extends UserItSupport {

    private static final long APPLICANT = 9001;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void clean() {
        cleanBusinessTables();
    }

    private JsonNode call(MockHttpServletRequestBuilder b) throws Exception {
        return om.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(body));
    }

    private static Map<String, String> form(String shop, String admin) {
        return Map.of("shopName", shop, "adminUsername", admin, "adminPassword", "password123");
    }

    private JsonNode submit(long applicant, String shop, String admin) throws Exception {
        return call(as(json(post("/merchant-applications"), form(shop, admin)), null, applicant, "BUYER"));
    }

    /** ID of the task in the platform admin's queue belonging to this process instance; null if none */
    private String platformTaskOf(String pid) throws Exception {
        for (JsonNode t : call(platformAdmin(get("/approvals/tasks"))).at("/data")) {
            if (t.at("/processInstanceId").asText().equals(pid)) {
                return t.at("/taskId").asText();
            }
        }
        return null;
    }

    private JsonNode login(String username) throws Exception {
        return call(json(post("/user/login"), Map.of("username", username, "password", "password123")));
    }

    @Test
    @DisplayName("入驻通过：建租户、开 MERCHANT_ADMIN 账号（能用申请时的密码登录）、申请变 30；实例结束 → 运行时任务清空，历史还在")
    void approve_createsTenantAndAdmin() throws Exception {
        JsonNode app = submit(APPLICANT, "九号小铺", "shop9_admin").at("/data");
        long appId = app.at("/id").asLong();
        String pid = app.at("/processInstanceId").asText();
        assertThat(app.at("/status").asInt()).isEqualTo(10);
        assertThat(runtimeTasks(pid)).isEqualTo(1);

        String taskId = platformTaskOf(pid);
        assertThat(taskId).isNotNull();
        assertThat(call(platformAdmin(json(post("/approvals/tasks/" + taskId + "/approve"), Map.of("comment", "资质齐全"))))
                .at("/code").asInt()).isEqualTo(200);

        JsonNode done = call(platformAdmin(get("/merchant-applications/" + appId))).at("/data");
        assertThat(done.at("/status").asInt()).isEqualTo(30);
        long tenantId = done.at("/tenantId").asLong();
        assertThat(jdbc.queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, tenantId)).isEqualTo("九号小铺");

        JsonNode token = login("shop9_admin").at("/data");
        assertThat(token.at("/role").asText()).isEqualTo("MERCHANT_ADMIN");
        assertThat(token.at("/tenantId").asLong()).isEqualTo(tenantId);

        assertThat(runtimeTasks(pid)).isZero();
        assertThat(historicTasks(pid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE ID_ = ?", Boolean.class, pid)).isTrue();
    }

    @Test
    @DisplayName("驳回 → 申请人修改重提 → 通过：始终是同一个流程实例，历史里有 审核、修改、审核 三条任务；建出来的是修改后的店名")
    void reject_resubmit_approve_sameInstance() throws Exception {
        JsonNode app = submit(APPLICANT, "九号小铺", "shop9_admin").at("/data");
        long appId = app.at("/id").asLong();
        String pid = app.at("/processInstanceId").asText();

        String review1 = platformTaskOf(pid);
        // Rejecting requires a reason
        assertThat(call(platformAdmin(json(post("/approvals/tasks/" + review1 + "/reject"), Map.of()))).at("/code").asInt())
                .isEqualTo(40000);
        call(platformAdmin(json(post("/approvals/tasks/" + review1 + "/reject"), Map.of("comment", "营业执照不清晰"))));

        JsonNode rejected = call(as(get("/merchant-applications/" + appId), null, APPLICANT, "BUYER")).at("/data");
        assertThat(rejected.at("/status").asInt()).isEqualTo(20);
        assertThat(rejected.at("/rejectReason").asText()).isEqualTo("营业执照不清晰");
        // Now it's the applicant's turn: not in the platform admin's queue, but "revise and resubmit" is in the applicant's own queue
        assertThat(platformTaskOf(pid)).isNull();
        JsonNode mine = call(as(get("/approvals/tasks"), null, APPLICANT, "BUYER")).at("/data");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).at("/taskKey").asText()).isEqualTo("applicantRevise");

        // Other buyers can't modify this application (treated as nonexistent)
        assertThat(call(as(json(put("/merchant-applications/" + appId), form("抢走", "thief_admin")), null, 9002, "BUYER"))
                .at("/code").asInt()).isEqualTo(40400);

        JsonNode resubmitted = call(as(json(put("/merchant-applications/" + appId), form("九号优选", "shop9_admin")),
                null, APPLICANT, "BUYER")).at("/data");
        assertThat(resubmitted.at("/status").asInt()).isEqualTo(10);
        assertThat(resubmitted.at("/submitCount").asInt()).isEqualTo(2);
        assertThat(resubmitted.at("/processInstanceId").asText()).isEqualTo(pid);

        String review2 = platformTaskOf(pid);
        assertThat(review2).isNotNull().isNotEqualTo(review1);
        call(platformAdmin(post("/approvals/tasks/" + review2 + "/approve")));

        JsonNode done = call(platformAdmin(get("/merchant-applications/" + appId))).at("/data");
        assertThat(done.at("/status").asInt()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, done.at("/tenantId").asLong()))
                .isEqualTo("九号优选");
        assertThat(runtimeTasks(pid)).isZero();
        assertThat(historicTasks(pid)).isEqualTo(3);
    }

    @Test
    @DisplayName("驳回后申请人撤回：申请 40、实例结束，不会永远挂在运行时表里；撤回后可以重新申请")
    void reject_withdraw_endsInstance() throws Exception {
        JsonNode app = submit(APPLICANT, "九号小铺", "shop9_admin").at("/data");
        long appId = app.at("/id").asLong();
        String pid = app.at("/processInstanceId").asText();
        call(platformAdmin(json(post("/approvals/tasks/" + platformTaskOf(pid) + "/reject"), Map.of("comment", "类目不符"))));

        // While one is in progress (in review / rejected), a second can't be opened
        assertThat(submit(APPLICANT, "第二家", "shop9_b").at("/code").asInt()).isEqualTo(63005);

        assertThat(call(as(post("/merchant-applications/" + appId + "/withdraw"), null, APPLICANT, "BUYER"))
                .at("/data/status").asInt()).isEqualTo(40);
        assertThat(runtimeTasks(pid)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ?", Integer.class, pid)).isZero();
        // A withdrawn application can't be resubmitted
        assertThat(call(as(json(put("/merchant-applications/" + appId), form("x", "shop9_x")), null, APPLICANT, "BUYER"))
                .at("/code").asInt()).isEqualTo(63003);
        assertThat(submit(APPLICANT, "第二家", "shop9_b").at("/code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("两张申请抢同一个登录名：先通过的拿到；后一张通过时 63004，整个事务回滚 —— 没建出租户，任务还在待办里")
    void usernameTakenAtApproval_rollsBackEverything() throws Exception {
        String pidA = submit(9001, "A 店", "same_admin").at("/data/processInstanceId").asText();
        String pidB = submit(9002, "B 店", "same_admin").at("/data/processInstanceId").asText();

        call(platformAdmin(post("/approvals/tasks/" + platformTaskOf(pidA) + "/approve")));
        String taskB = platformTaskOf(pidB);
        assertThat(call(platformAdmin(post("/approvals/tasks/" + taskB + "/approve"))).at("/code").asInt()).isEqualTo(63004);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM merchant_application WHERE process_instance_id = ?", Integer.class, pidB))
                .isEqualTo(10);
        assertThat(platformTaskOf(pidB)).isEqualTo(taskB);
    }

    @Test
    @DisplayName("权限：商家管理员看不到入驻待办、办理返回 63001；买家不能审批（403）；员工没有待办接口（403）")
    void onlyPlatformAdminReviewsOnboarding() throws Exception {
        String pid = submit(APPLICANT, "九号小铺", "shop9_admin").at("/data/processInstanceId").asText();
        String taskId = platformTaskOf(pid);

        assertThat(call(as(get("/approvals/tasks"), 7L, 2001, "MERCHANT_ADMIN")).at("/data")).isEmpty();
        assertThat(call(as(post("/approvals/tasks/" + taskId + "/approve"), 7L, 2001, "MERCHANT_ADMIN")).at("/code").asInt())
                .isEqualTo(63001);
        mvc.perform(as(post("/approvals/tasks/" + taskId + "/approve"), null, APPLICANT, "BUYER")).andExpect(status().isForbidden());
        mvc.perform(as(get("/approvals/tasks"), 7L, 2002, "MERCHANT_STAFF")).andExpect(status().isForbidden());
        mvc.perform(get("/approvals/tasks")).andExpect(status().isUnauthorized());
        assertThat(runtimeTasks(pid)).isEqualTo(1);
    }
}
