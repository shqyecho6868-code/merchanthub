package com.merchanthub.product.catalog;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.product.ProductItSupport;
import com.merchanthub.product.catalog.client.ListingApprovalClient;
import com.merchanthub.product.catalog.client.ListingApprovalClient.ListingResult;
import com.merchanthub.product.catalog.client.ListingApprovalClient.StartListing;
import com.merchanthub.product.catalog.job.ListingAuditCompensator;
import com.merchanthub.product.catalog.service.ListingStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品上架审批（D1，product 侧）：提交 → Feign 发起（user-tenant 用替身）→ 结果回写（幂等、防乱序）→ 补偿扫描。
 * 场景：商家 7 的商品"旗舰手机"（草稿），员工 2002 提交；商家 8 的员工 3002 想动它。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductListingIT extends ProductItSupport {

    private static final long M7 = 7;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ListingStateService stateService;
    @Autowired
    private ListingAuditCompensator compensator;
    @MockitoBean
    private ListingApprovalClient approvalClient;

    private long productId;

    @Override
    protected boolean defaultSystemContext() {
        return false;
    }

    @BeforeEach
    void seed() {
        reset(approvalClient);
        jdbc.execute("TRUNCATE TABLE product");
        jdbc.execute("TRUNCATE TABLE product_status_log");
        jdbc.update("INSERT INTO product (tenant_id, name, status) VALUES (?, '旗舰手机', 0)", M7);
        productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, long tenant, long user, String role) {
        return b.header("X-Tenant-Id", tenant).header("X-User-Id", user).header("X-Role", role);
    }

    private MockHttpServletRequestBuilder submitAs7() {
        return as(post("/products/" + productId + "/listing"), M7, 2002, "MERCHANT_STAFF");
    }

    private int productStatus() {
        return jdbc.queryForObject("SELECT status FROM product WHERE id = ?", Integer.class, productId);
    }

    private String instance() {
        return jdbc.queryForObject("SELECT audit_instance_id FROM product WHERE id = ?", String.class, productId);
    }

    private int logs(String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_status_log WHERE product_id = ? AND event = ?",
                Integer.class, productId, event);
    }

    /** 模拟消费者 / 补偿以"商家 7"身份回写（生产里由 TenantMessages.consumeAs 恢复上下文） */
    private boolean apply(int round, boolean approved, String pid) {
        return TenantContext.callAs(TenantInfo.systemOf(M7),
                () -> stateService.applyResult(productId, round, approved, approved ? null : "主图不清晰", pid, "MQ"));
    }

    private void makeStale() {
        jdbc.update("UPDATE product SET audit_submitted_at = NOW(3) - INTERVAL 10 MINUTE WHERE id = ?", productId);
    }

    @Test
    @DisplayName("提交：草稿 → 审核中、第 1 轮、回填实例 ID、写流水；Feign 带上商家身份发起；买家看不到（order 只认 status=2）")
    void submit_startsApproval() throws Exception {
        AtomicReference<TenantInfo> seenByFeign = new AtomicReference<>();
        when(approvalClient.start(any())).thenAnswer(inv -> {
            seenByFeign.set(TenantContext.get());   // TenantFeignInterceptor 据此写请求头
            return ResultUtils.success("pid-1");
        });

        mvc.perform(submitAs7()).andExpect(jsonPath("$.data.auditRound").value(1))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.processInstanceId").value("pid-1"));

        assertThat(productStatus()).isEqualTo(1);
        assertThat(instance()).isEqualTo("pid-1");
        assertThat(logs("SUBMIT_LISTING")).isEqualTo(1);
        verify(approvalClient).start(new StartListing(productId, 1, "旗舰手机"));
        assertThat(seenByFeign.get().tenantId()).isEqualTo(M7);
        assertThat(seenByFeign.get().userId()).isEqualTo(2002L);
    }

    @Test
    @DisplayName("审核中不能再提交（60008）；别的商家提交 = 不存在（40400），都不会去发起审批")
    void submit_rejectsInvalidState_andOtherTenant() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"));
        mvc.perform(submitAs7());
        mvc.perform(submitAs7()).andExpect(jsonPath("$.code").value(60008));
        verify(approvalClient, times(1)).start(any());

        jdbc.update("UPDATE product SET status = 0 WHERE id = ?", productId);
        mvc.perform(as(post("/products/" + productId + "/listing"), 8, 3002, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.code").value(40400));
        // 平台管理员不替商家提交
        mvc.perform(post("/products/" + productId + "/listing").header("X-User-Id", 1).header("X-Role", "PLATFORM_ADMIN"))
                .andExpect(status().isForbidden());
        verify(approvalClient, times(1)).start(any());
    }

    @Test
    @DisplayName("通过的结果：审核中 → 已上架；同一条结果再来两次（重复投递）不再改、流水只有一条")
    void approved_appliedOnce() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"));
        mvc.perform(submitAs7());

        assertThat(apply(1, true, "pid-1")).isTrue();
        assertThat(apply(1, true, "pid-1")).isFalse();
        assertThat(apply(1, true, "pid-1")).isFalse();

        assertThat(productStatus()).isEqualTo(2);
        assertThat(logs("LISTING_APPROVED")).isEqualTo(1);
        mvc.perform(as(get("/products/" + productId), M7, 2002, "MERCHANT_STAFF")).andExpect(jsonPath("$.data.status").value(2));
    }

    @Test
    @DisplayName("乱序：第 1 轮驳回 → 改完提交第 2 轮 → 第 1 轮的迟到消息（哪怕说'通过'）改不了第 2 轮；已上架的不会被迟到的驳回打回去")
    void staleRoundResult_ignored() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"), ResultUtils.success("pid-2"));
        mvc.perform(submitAs7());
        assertThat(apply(1, false, "pid-1")).isTrue();
        assertThat(productStatus()).isEqualTo(4);
        mvc.perform(as(get("/products/" + productId), M7, 2002, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.data.rejectReason").value("主图不清晰"));

        mvc.perform(submitAs7()).andExpect(jsonPath("$.data.auditRound").value(2));
        assertThat(apply(1, true, "pid-1")).isFalse();
        assertThat(productStatus()).isEqualTo(1);

        assertThat(apply(2, true, "pid-2")).isTrue();
        assertThat(apply(2, false, "pid-2")).isFalse();
        assertThat(productStatus()).isEqualTo(2);
        assertThat(instance()).isEqualTo("pid-2");
    }

    @Test
    @DisplayName("Feign 发起失败：商品停在审核中、无实例 ID（不回滚提交）；补偿反查 NOT_FOUND → 重新发起并回填")
    void startFailed_compensatorRestarts() throws Exception {
        when(approvalClient.start(any())).thenThrow(new RuntimeException("Read timed out"));
        mvc.perform(submitAs7()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.processInstanceId").doesNotExist());
        assertThat(productStatus()).isEqualTo(1);
        assertThat(instance()).isNull();

        // 还没到 older-than：补偿不碰（正常审批中的商品不该每轮都去问）
        assertThat(compensator.scan()).isZero();
        verify(approvalClient, never()).result(anyLong(), anyInt());

        makeStale();
        reset(approvalClient);
        when(approvalClient.result(productId, 1)).thenReturn(ResultUtils.success(new ListingResult(ListingResult.NOT_FOUND, null, null)));
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-retry"));
        assertThat(compensator.scan()).isEqualTo(1);
        assertThat(instance()).isEqualTo("pid-retry");
        assertThat(productStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("消息丢了：补偿反查到 APPROVED → 回写已上架（来源 COMPENSATE）；之后消息姗姗来迟也不重复改")
    void messageLost_compensatorApplies() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"));
        mvc.perform(submitAs7());
        makeStale();
        when(approvalClient.result(productId, 1)).thenReturn(ResultUtils.success(new ListingResult("APPROVED", "pid-1", null)));

        assertThat(compensator.scan()).isEqualTo(1);
        assertThat(productStatus()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source FROM product_status_log WHERE event = 'LISTING_APPROVED'", String.class))
                .isEqualTo("COMPENSATE");
        assertThat(apply(1, true, "pid-1")).isFalse();
        assertThat(logs("LISTING_APPROVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("补偿：反查 RUNNING（审批人还没处理）不改状态、只补实例 ID；反查失败 / 返回错误就跳过")
    void compensator_runningOrUnavailable() throws Exception {
        when(approvalClient.start(any())).thenReturn(BaseResponse.class.cast(ResultUtils.error(
                com.merchanthub.common.common.ErrorCode.SYSTEM_ERROR)));
        mvc.perform(submitAs7());
        assertThat(instance()).isNull();
        makeStale();

        when(approvalClient.result(productId, 1)).thenThrow(new RuntimeException("Connection refused"));
        assertThat(compensator.scan()).isZero();

        reset(approvalClient);
        when(approvalClient.result(productId, 1)).thenReturn(BaseResponse.class.cast(ResultUtils.error(
                com.merchanthub.common.common.ErrorCode.SYSTEM_ERROR)));
        assertThat(compensator.scan()).isZero();

        reset(approvalClient);
        when(approvalClient.result(productId, 1)).thenReturn(ResultUtils.success(new ListingResult("RUNNING", "pid-1", null)));
        assertThat(compensator.scan()).isEqualTo(1);
        assertThat(productStatus()).isEqualTo(1);
        assertThat(instance()).isEqualTo("pid-1");
        // 再扫一次：实例 ID 已有，RUNNING 什么都不做
        assertThat(compensator.scan()).isZero();
    }
}
