package com.merchanthub.realtime;

import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.security.JwtCodec;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.realtime.netty.NettyServer;
import com.merchanthub.realtime.push.PushTopics;
import com.merchanthub.realtime.route.RouteRegistry;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * E1: two realtime nodes run in the same JVM (each with its own Spring context, Netty port and dedicated consumer group), sharing real MySQL / Redis / RocketMQ.
 * <ul>
 *   <li>Node A: only accepts connections, <b>does not consume</b> order events</li>
 *   <li>Node B: consumes order events</li>
 * </ul>
 * So there is only one way for "someone connected to A receives a push": B consumes -> looks up the route table and gets A -> forwards via REALTIME_PUSH (Tag = A) -> A writes to the connection.
 */
class RealtimePushIT {

    private static final String SECRET = "bWVyY2hhbnRodWItdGVzdC1zZWNyZXQtMzItYnl0ZXMhIQ==";
    private static final long TENANT = 7L;
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;
    private static DefaultMQProducer orderProducer;
    private static StringRedisTemplate redis;
    private static JdbcTemplate jdbc;
    private static final JwtCodec JWT = new JwtCodec(SECRET);

    private static ConfigurableApplicationContext startNode(String nodeId, boolean consumeOrders) {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", SharedContainers.mysql().getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8");
        p.put("spring.datasource.username", SharedContainers.mysql().getUsername());
        p.put("spring.datasource.password", SharedContainers.mysql().getPassword());
        p.put("spring.data.redis.host", SharedContainers.redis().getHost());
        p.put("spring.data.redis.port", SharedContainers.redis().getMappedPort(6379));
        p.put("spring.data.redis.password", "");
        p.put("merchanthub.rocketmq.namesrv-addr", SharedContainers.rocketmq().getNamesrvAddr());
        p.put("spring.cloud.nacos.discovery.enabled", "false");
        p.put("spring.cloud.service-registry.auto-registration.enabled", "false");
        p.put("server.port", "0");
        p.put("merchanthub.jwt.secret", SECRET);
        p.put("merchanthub.realtime.node-id", nodeId);
        p.put("merchanthub.realtime.netty-port", "0");
        p.put("merchanthub.realtime.route-ttl", "3s");
        p.put("merchanthub.realtime.order-consumer.enabled", String.valueOf(consumeOrders));
        p.put("merchanthub.realtime.order-consumer.consume-from", "CONSUME_FROM_FIRST_OFFSET");
        // Pass as command-line args: builder.properties() are lowest-precedence defaults and would be overridden by ${JWT_SECRET} etc. in application.yml
        return new SpringApplicationBuilder(RealTimeServiceApplication.class)
                .run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    @BeforeAll
    static void startNodes() throws Exception {
        SharedContainers.rocketmq().createTopic(PushTopics.ORDER_TOPIC);
        SharedContainers.rocketmq().createTopic(PushTopics.PUSH_TOPIC);
        nodeA = startNode("node-a", false);
        nodeB = startNode("node-b", true);
        redis = nodeA.getBean(StringRedisTemplate.class);
        jdbc = nodeA.getBean(JdbcTemplate.class);
        orderProducer = new DefaultMQProducer("it-realtime-order-producer");
        orderProducer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        orderProducer.setVipChannelEnabled(false);
        orderProducer.start();
    }

    @AfterAll
    static void stop() {
        orderProducer.shutdown();
        if (nodeA != null && nodeA.isActive()) {
            nodeA.close();
        }
        nodeB.close();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE offline_message");
        jdbc.execute("TRUNCATE TABLE merchant_order_notice");
    }

    // ---------------- Helpers ----------------

    /** Simulate a user-tenant login: sign a token and store it in Redis (compared during the handshake) */
    private static String login(long userId, Long tenantId, String role) {
        String token = JWT.issue(userId, tenantId, role, Duration.ofHours(1));
        redis.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + userId, token);
        return token;
    }

    private static int port(ConfigurableApplicationContext node) {
        return node.getBean(NettyServer.class).port();
    }

    /** Collects received text frames */
    static final class Client implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocket ws;
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                frames.add(buf.toString());
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        String next() throws InterruptedException {
            return frames.poll(30, TimeUnit.SECONDS);
        }
    }

    private static Client connect(ConfigurableApplicationContext node, String token) {
        Client c = new Client();
        c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://127.0.0.1:" + port(node) + NettyServer.WS_PATH), c).join();
        return c;
    }

    private static java.util.Set<Object> routeOf(String receiverKey) {
        return redis.opsForHash().keys(RouteRegistry.PREFIX + receiverKey);
    }

    private static void sendOrderEvent(String tag, String json) throws Exception {
        orderProducer.send(TenantMessages.stamp(new Message(PushTopics.ORDER_TOPIC, tag, json.getBytes(StandardCharsets.UTF_8)), TENANT));
    }

    private static long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ---------------- Test cases ----------------

    /**
     * Cross-node push: merchant 7's staff is connected to node A, ORDER_CREATED is consumed by node B -> the merchant receives the new-order alert on A.
     * The same event delivered twice: the notice is written once and pushed once (consumer dedup)
     */
    @Test
    void merchantOnNodeA_eventConsumedByNodeB_pushedViaRoute() throws Exception {
        Client merchant = connect(nodeA, login(501, TENANT, "MERCHANT_ADMIN"));
        await().atMost(Duration.ofSeconds(10)).until(() -> routeOf("merchant:7").contains("node-a"));

        long eventId = IDS.incrementAndGet(), orderNo = IDS.incrementAndGet();
        String json = "{\"eventId\":" + eventId + ",\"orderNo\":" + orderNo + ",\"tenantId\":7,\"buyerId\":1001,"
                + "\"payAmount\":9900,\"createdAt\":\"2026-11-23T00:00:00\"}";
        sendOrderEvent(PushTopics.TAG_ORDER_CREATED, json);
        sendOrderEvent(PushTopics.TAG_ORDER_CREATED, json);   // duplicate delivery

        String frame = merchant.next();
        assertThat(frame).isNotNull().contains("\"type\":\"NEW_ORDER\"").contains("\"orderNo\":\"" + orderNo + "\"");
        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("SELECT COUNT(*) FROM offline_message WHERE msg_id = ? AND status = 1", "evt-" + eventId + "-m") == 1);
        assertThat(count("SELECT COUNT(*) FROM merchant_order_notice WHERE order_no = ?", orderNo)).isEqualTo(1);
        assertThat(merchant.frames.poll(3, TimeUnit.SECONDS)).as("重复事件不再推").isNull();

        // Heartbeat: reply pong and renew the route
        merchant.ws.sendText("ping", true).join();
        assertThat(merchant.next()).isEqualTo("pong");

        // Normal disconnect: A unregisters its own route
        merchant.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        await().atMost(Duration.ofSeconds(10)).until(() -> !routeOf("merchant:7").contains("node-a"));
    }

    /**
     * Node crash: buyer 1001 is connected to some node X, and X is kill -9'd (no chance to unregister its route).
     * Simulated by "writing an X field into the route table while no node is named X": B consumes ORDER_PAID -> route says X -> forwards to X -> nobody receives it.
     * Result: the message was stored as "pending" before dispatch, so it isn't lost; X's route expires after 3 seconds without renewal; the buyer reconnects to B -> redelivered after a successful handshake
     */
    @Test
    void nodeCrashed_routeExpires_buyerReconnectsElsewhere_getsReplay() throws Exception {
        redis.execute(new DefaultRedisScript<>("redis.call('HSET', KEYS[1], ARGV[1], '0') "
                + "return redis.call('HEXPIRE', KEYS[1], 3, 'FIELDS', 1, ARGV[1])[1]", Long.class),
                List.of(RouteRegistry.PREFIX + "buyer:1001"), "node-crashed");

        long eventId = IDS.incrementAndGet(), orderNo = IDS.incrementAndGet();
        sendOrderEvent(PushTopics.TAG_ORDER_PAID, "{\"eventId\":" + eventId + ",\"orderNo\":" + orderNo
                + ",\"tenantId\":7,\"event\":\"PAY_SUCCESS\",\"buyerId\":1001}");

        String msgId = "evt-" + eventId + "-b";
        await().atMost(Duration.ofSeconds(30)).until(() ->
                count("SELECT COUNT(*) FROM offline_message WHERE msg_id = ? AND status = 0", msgId) == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> routeOf("buyer:1001").isEmpty());   // TTL expired

        Client buyer = connect(nodeB, login(1001, null, "BUYER"));
        String frame = buyer.next();
        assertThat(frame).isNotNull().contains("\"type\":\"ORDER_STATUS\"").contains("\"status\":20")
                .contains("\"orderNo\":\"" + orderNo + "\"");
        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("SELECT COUNT(*) FROM offline_message WHERE msg_id = ? AND status = 1", msgId) == 1);
        assertThat(routeOf("buyer:1001")).containsExactly("node-b");
        buyer.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    /** Fully offline: the message stays in the table; on coming online (to any node) it is redelivered, and another staff member of the same shop also gets merchant notices */
    @Test
    void offlineMerchant_getsReplayOnConnect() throws Exception {
        long eventId = IDS.incrementAndGet(), orderNo = IDS.incrementAndGet();
        sendOrderEvent(PushTopics.TAG_ORDER_CREATED, "{\"eventId\":" + eventId + ",\"orderNo\":" + orderNo
                + ",\"tenantId\":8,\"buyerId\":1002,\"payAmount\":100}");
        await().atMost(Duration.ofSeconds(30)).until(() ->
                count("SELECT COUNT(*) FROM offline_message WHERE receiver_key = 'merchant:8' AND status = 0") == 1);

        Client staff = connect(nodeA, login(601, 8L, "MERCHANT_STAFF"));
        assertThat(staff.next()).contains("\"orderNo\":\"" + orderNo + "\"");
        staff.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    /** Handshake auth: forged token / token superseded by a new login / platform admin (no order pushes) all fail to connect */
    @Test
    void handshake_rejectsInvalidTokens() {
        assertThatThrownBy(() -> connect(nodeA, "not-a-jwt")).isInstanceOf(CompletionException.class);

        String old = login(701, TENANT, "MERCHANT_ADMIN");
        // Log in again so the old token is invalidated (use a different TTL: two tokens signed within the same second are identical)
        redis.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + 701,
                JWT.issue(701, TENANT, "MERCHANT_ADMIN", Duration.ofHours(2)));
        assertThatThrownBy(() -> connect(nodeA, old)).isInstanceOf(CompletionException.class);

        assertThatThrownBy(() -> connect(nodeA, login(801, null, "PLATFORM_ADMIN"))).isInstanceOf(CompletionException.class);
    }
}
