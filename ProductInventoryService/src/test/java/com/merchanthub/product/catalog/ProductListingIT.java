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
 * Product listing approval (D1, product side): submit → Feign start (user-tenant stubbed) → result write-back (idempotent, out-of-order safe) → compensation scan.
 * Scenario: merchant 7's product "Flagship Phone" (draft) is submitted by staff 2002; merchant 8's staff 3002 tries to touch it.
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
        jdbc.update("INSERT INTO product (tenant_id, name, status) VALUES (?, 'Flagship Phone', 0)", M7);
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

    /** Simulates the consumer / compensation writing back as "merchant 7" (in production TenantMessages.consumeAs restores the context) */
    private boolean apply(int round, boolean approved, String pid) {
        return TenantContext.callAs(TenantInfo.systemOf(M7),
                () -> stateService.applyResult(productId, round, approved, approved ? null : "Main image is blurry", pid, "MQ"));
    }

    private void makeStale() {
        jdbc.update("UPDATE product SET audit_submitted_at = NOW(3) - INTERVAL 10 MINUTE WHERE id = ?", productId);
    }

    @Test
    @DisplayName("Submit: draft → under review, round 1, instance ID backfilled, history written; Feign starts it with the merchant identity; invisible to buyers (order only accepts status=2)")
    void submit_startsApproval() throws Exception {
        AtomicReference<TenantInfo> seenByFeign = new AtomicReference<>();
        when(approvalClient.start(any())).thenAnswer(inv -> {
            seenByFeign.set(TenantContext.get());   // TenantFeignInterceptor writes the request headers from this
            return ResultUtils.success("pid-1");
        });

        mvc.perform(submitAs7()).andExpect(jsonPath("$.data.auditRound").value(1))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.processInstanceId").value("pid-1"));

        assertThat(productStatus()).isEqualTo(1);
        assertThat(instance()).isEqualTo("pid-1");
        assertThat(logs("SUBMIT_LISTING")).isEqualTo(1);
        verify(approvalClient).start(new StartListing(productId, 1, "Flagship Phone"));
        assertThat(seenByFeign.get().tenantId()).isEqualTo(M7);
        assertThat(seenByFeign.get().userId()).isEqualTo(2002L);
    }

    @Test
    @DisplayName("Can't resubmit while under review (60008); another merchant submitting = not found (40400); neither starts an approval")
    void submit_rejectsInvalidState_andOtherTenant() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"));
        mvc.perform(submitAs7());
        mvc.perform(submitAs7()).andExpect(jsonPath("$.code").value(60008));
        verify(approvalClient, times(1)).start(any());

        jdbc.update("UPDATE product SET status = 0 WHERE id = ?", productId);
        mvc.perform(as(post("/products/" + productId + "/listing"), 8, 3002, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.code").value(40400));
        // The platform admin doesn't submit on a merchant's behalf
        mvc.perform(post("/products/" + productId + "/listing").header("X-User-Id", 1).header("X-Role", "PLATFORM_ADMIN"))
                .andExpect(status().isForbidden());
        verify(approvalClient, times(1)).start(any());
    }

    @Test
    @DisplayName("Approved result: under review → listed; the same result twice more (redelivery) changes nothing, only one history row")
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
    @DisplayName("Out of order: round 1 rejected → fixed and resubmitted as round 2 → round 1's late message (even 'approved') can't change round 2; a listed product isn't knocked back by a late rejection")
    void staleRoundResult_ignored() throws Exception {
        when(approvalClient.start(any())).thenReturn(ResultUtils.success("pid-1"), ResultUtils.success("pid-2"));
        mvc.perform(submitAs7());
        assertThat(apply(1, false, "pid-1")).isTrue();
        assertThat(productStatus()).isEqualTo(4);
        mvc.perform(as(get("/products/" + productId), M7, 2002, "MERCHANT_STAFF"))
                .andExpect(jsonPath("$.data.rejectReason").value("Main image is blurry"));

        mvc.perform(submitAs7()).andExpect(jsonPath("$.data.auditRound").value(2));
        assertThat(apply(1, true, "pid-1")).isFalse();
        assertThat(productStatus()).isEqualTo(1);

        assertThat(apply(2, true, "pid-2")).isTrue();
        assertThat(apply(2, false, "pid-2")).isFalse();
        assertThat(productStatus()).isEqualTo(2);
        assertThat(instance()).isEqualTo("pid-2");
    }

    @Test
    @DisplayName("Feign start fails: product stays under review without an instance ID (submit not rolled back); compensation query NOT_FOUND → restart and backfill")
    void startFailed_compensatorRestarts() throws Exception {
        when(approvalClient.start(any())).thenThrow(new RuntimeException("Read timed out"));
        mvc.perform(submitAs7()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.processInstanceId").doesNotExist());
        assertThat(productStatus()).isEqualTo(1);
        assertThat(instance()).isNull();

        // Not past older-than yet: compensation leaves it alone (products in normal review shouldn't be queried every round)
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
    @DisplayName("Message lost: compensation query finds APPROVED → written back as listed (source COMPENSATE); the late message that arrives afterwards changes nothing")
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
    @DisplayName("Compensation: query RUNNING (approver hasn't acted) doesn't change status, only backfills the instance ID; query failure / error response is skipped")
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
        // Scan again: instance ID already present, RUNNING does nothing
        assertThat(compensator.scan()).isZero();
    }
}
