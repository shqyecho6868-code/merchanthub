package com.merchanthub.user.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.testsupport.SharedContainers;
import com.merchanthub.user.UserItSupport;
import com.merchanthub.user.approval.mq.ApprovalOutboxRelay;
import com.merchanthub.user.approval.mq.ListingResultEvent;
import com.merchanthub.user.approval.service.ApprovalTaskService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product listing approval (D1, user-tenant side): product starts it -> merchant 7 admin first review -> platform admin second review -> on end, write approval record + local outbox table -> RocketMQ.
 * Scenario: merchant 7's staff 2002 submits product 1001 "Flagship Phone" for round 1 review; merchant 7 admin 2001; merchant 8 admin 3001; platform admin 1.
 */
class ListingApprovalIT extends UserItSupport {

    private static final long M7 = 7;
    private static final List<MessageExt> RECEIVED = new CopyOnWriteArrayList<>();
    private static DefaultMQPushConsumer consumer;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ApprovalTaskService taskService;
    @Autowired
    private ApprovalOutboxRelay relay;

    /** Simulates product: subscribes to the approval result topic to check the message was really sent */
    @BeforeAll
    static void subscribe() throws Exception {
        SharedContainers.rocketmq().createTopic(ListingResultEvent.TOPIC);
        consumer = new DefaultMQPushConsumer("it-listing-result-" + System.nanoTime());
        consumer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(ListingResultEvent.TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            RECEIVED.addAll(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    @AfterAll
    static void unsubscribe() {
        consumer.shutdown();
    }

    @BeforeEach
    void clean() {
        cleanBusinessTables();
    }

    private JsonNode call(MockHttpServletRequestBuilder b) throws Exception {
        return om.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }

    /** product calls the internal endpoint via Feign as "merchant 7's staff 2002" (TenantFeignInterceptor forwards the identity headers) */
    private String start(long tenant, long productId, int round) throws Exception {
        return call(as(post("/internal/approvals/product-listings").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("productId", productId, "round", round, "productName", "旗舰手机"))),
                tenant, 2002, "MERCHANT_STAFF")).at("/data").asText();
    }

    private String taskOf(MockHttpServletRequestBuilder listAs, String pid) throws Exception {
        for (JsonNode t : call(listAs).at("/data")) {
            if (t.at("/processInstanceId").asText().equals(pid)) {
                return t.at("/taskId").asText();
            }
        }
        return null;
    }

    private MockHttpServletRequestBuilder m7Admin(MockHttpServletRequestBuilder b) {
        return as(b, M7, 2001, "MERCHANT_ADMIN");
    }

    private MockHttpServletRequestBuilder m8Admin(MockHttpServletRequestBuilder b) {
        return as(b, 8L, 3001, "MERCHANT_ADMIN");
    }

    private JsonNode result(long tenant, long productId, int round) throws Exception {
        return call(as(get("/internal/approvals/product-listings/" + productId + "/rounds/" + round), tenant, 0, "SYSTEM")).at("/data");
    }

    @Test
    @DisplayName("完整通过：初审 → 复审 → 审批单 APPROVED、outbox 一条 APPROVED 消息并真的投递到 MQ（带 tenantId 属性）；运行时任务清空、历史 2 条")
    void fullApproval_publishesResult() throws Exception {
        String pid = start(M7, 1001, 1);
        assertThat(result(M7, 1001, 1).at("/state").asText()).isEqualTo("RUNNING");

        // Step one is only in merchant 7 admin's queue
        String t1 = taskOf(m7Admin(get("/approvals/tasks")), pid);
        assertThat(t1).isNotNull();
        assertThat(taskOf(platformAdmin(get("/approvals/tasks")), pid)).isNull();
        assertThat(call(m7Admin(post("/approvals/tasks/" + t1 + "/approve"))).at("/code").asInt()).isEqualTo(200);
        assertThat(runtimeTasks(pid)).isEqualTo(1);
        assertThat(historicTasks(pid)).isEqualTo(2);   // one completed (has END_TIME_), one just created

        // Step two moved to the platform admin; it's gone from merchant 7 admin's queue
        assertThat(taskOf(m7Admin(get("/approvals/tasks")), pid)).isNull();
        String t2 = taskOf(platformAdmin(get("/approvals/tasks")), pid);
        assertThat(call(platformAdmin(post("/approvals/tasks/" + t2 + "/approve"))).at("/code").asInt()).isEqualTo(200);

        assertThat(runtimeTasks(pid)).isZero();
        assertThat(historicTasks(pid)).isEqualTo(2);
        JsonNode r = result(M7, 1001, 1);
        assertThat(r.at("/state").asText()).isEqualTo("APPROVED");
        assertThat(r.at("/processInstanceId").asText()).isEqualTo(pid);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_outbox", Integer.class)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.queryForObject("SELECT status FROM approval_outbox", Integer.class) == 1);
        await().atMost(Duration.ofSeconds(60)).until(() -> received(pid) != null);
        MessageExt msg = received(pid);
        assertThat(TenantMessages.tenantIdOf(msg)).isEqualTo(M7);
        JsonNode event = om.readTree(new String(msg.getBody(), StandardCharsets.UTF_8));
        assertThat(event.at("/result").asText()).isEqualTo("APPROVED");
        assertThat(event.at("/productId").asLong()).isEqualTo(1001);
        assertThat(event.at("/round").asInt()).isEqualTo(1);
    }

    private MessageExt received(String pid) {
        return RECEIVED.stream().filter(m -> new String(m.getBody(), StandardCharsets.UTF_8).contains(pid)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("初审驳回：实例直接结束（不进复审），审批单 REJECTED + 原因，outbox 一条 REJECTED")
    void merchantReject_endsImmediately() throws Exception {
        String pid = start(M7, 1002, 1);
        String t1 = taskOf(m7Admin(get("/approvals/tasks")), pid);
        call(m7Admin(post("/approvals/tasks/" + t1 + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"主图不清晰\"}")));

        assertThat(runtimeTasks(pid)).isZero();
        JsonNode r = result(M7, 1002, 1);
        assertThat(r.at("/state").asText()).isEqualTo("REJECTED");
        assertThat(r.at("/rejectReason").asText()).isEqualTo("主图不清晰");
        String payload = jdbc.queryForObject("SELECT payload FROM approval_outbox", String.class);
        assertThat(om.readTree(payload).at("/result").asText()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("发起幂等：同一 (商家, 商品, 轮次) 重复发起返回同一个实例；新一轮是新实例")
    void start_isIdempotentPerRound() throws Exception {
        String pid = start(M7, 1003, 1);
        assertThat(start(M7, 1003, 1)).isEqualTo(pid);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_approval", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE BUSINESS_KEY_ = 'listing:7:1003:1'",
                Integer.class)).isEqualTo(1);
        assertThat(start(M7, 1003, 2)).isNotEqualTo(pid);
    }

    @Test
    @DisplayName("租户隔离：商家 8 管理员看不到、办不了商家 7 的待办（63001）；商家 8 查商家 7 的审批单 = NOT_FOUND")
    void otherTenantCannotSeeOrHandle() throws Exception {
        String pid = start(M7, 1004, 1);
        String t1 = taskOf(m7Admin(get("/approvals/tasks")), pid);

        assertThat(taskOf(m8Admin(get("/approvals/tasks")), pid)).isNull();
        assertThat(call(m8Admin(post("/approvals/tasks/" + t1 + "/approve"))).at("/code").asInt()).isEqualTo(63001);
        // Even the platform admin can't skip the merchant's first review
        assertThat(call(platformAdmin(post("/approvals/tasks/" + t1 + "/approve"))).at("/code").asInt()).isEqualTo(63001);
        // Merchant staff can start it but can't approve (endpoint-level RBAC)
        mvc.perform(as(post("/approvals/tasks/" + t1 + "/approve"), M7, 2002, "MERCHANT_STAFF")).andExpect(status().isForbidden());
        assertThat(runtimeTasks(pid)).isEqualTo(1);

        assertThat(result(8, 1004, 1).at("/state").asText()).isEqualTo("NOT_FOUND");
        assertThat(result(M7, 1004, 1).at("/state").asText()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("两个平台管理员同时点通过：只有一个成功，另一个失败（63002 或 63001）；审批单只记一次、只有一条待发消息")
    void concurrentApprove_onlyOneWins() throws Exception {
        String pid = start(M7, 1005, 1);
        call(m7Admin(post("/approvals/tasks/" + taskOf(m7Admin(get("/approvals/tasks")), pid) + "/approve")));
        String t2 = taskOf(platformAdmin(get("/approvals/tasks")), pid);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new java.util.ArrayList<>();
        for (long admin : new long[]{1, 2}) {
            results.add(pool.submit(() -> {
                go.await();
                // Worker threads don't have the test thread's context, so set it here (in production the web interceptor sets it from the request headers)
                try (TenantContext.Scope ignored = TenantContext.open(new TenantInfo(null, admin, Role.PLATFORM_ADMIN))) {
                    taskService.decide(t2, true, "ok");
                    return "OK";
                } catch (BusinessException e) {
                    return String.valueOf(e.getCode());
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new java.util.ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        // The loser has two legitimate outcomes, depending on whether its "find task" step lands before or after the winner commits:
        //   before -> it found the task, and complete hits the optimistic lock (REV_ changed / task deleted) -> 63002
        //   after -> the task is no longer in the runtime table, handled as "nonexistent" -> 63001 (more likely on slow machines like CI)
        assertThat(outcomes).hasSize(2).containsOnlyOnce("OK");
        assertThat(outcomes).filteredOn(o -> !o.equals("OK")).singleElement().isIn("63001", "63002");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_outbox", Integer.class)).isEqualTo(1);
        assertThat(result(M7, 1005, 1).at("/state").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("补偿扫描：快路径没发出去的记录（模拟进程在提交后、发送前挂掉）由 scan() 补发")
    void scanRepublishesPending() throws Exception {
        String pid = start(M7, 1006, 1);
        call(m7Admin(post("/approvals/tasks/" + taskOf(m7Admin(get("/approvals/tasks")), pid) + "/approve")));
        call(platformAdmin(post("/approvals/tasks/" + taskOf(platformAdmin(get("/approvals/tasks")), pid) + "/approve")));
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.queryForObject("SELECT status FROM approval_outbox", Integer.class) == 1);

        // Set it back to "pending", as if the fast path never ran
        jdbc.update("UPDATE approval_outbox SET status = 0, sent_at = NULL, next_retry_at = NOW(3) - INTERVAL 1 SECOND");
        relay.scan();   // E3: equivalent to XXL-Job triggering approvalOutboxRelayJob once
        assertThat(jdbc.queryForObject("SELECT status FROM approval_outbox", Integer.class)).isEqualTo(1);
        // The message arrived at least once (duplicate delivery is stopped by the CAS in product's consumer, see product's ListingResultConsumerIT)
        await().atMost(Duration.ofSeconds(60)).until(() -> received(pid) != null);
    }
}
