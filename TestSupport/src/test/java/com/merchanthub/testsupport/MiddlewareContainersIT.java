package com.merchanthub.testsupport;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the shared containers (lived in Common since A2+, moved to TestSupport with the containers in B1):
 * proves the three singleton containers MySQL / Redis / RocketMQ start and are reachable both locally and in CI. No business logic.
 */
class MiddlewareContainersIT {

    @Test
    void mysql_canCreateTableAndQuery() throws Exception {
        MySQLContainer<?> mysql = SharedContainers.mysql();
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t_ping (id BIGINT PRIMARY KEY, amount BIGINT NOT NULL)");
            st.execute("INSERT INTO t_ping VALUES (1, 1999)");
            try (ResultSet rs = st.executeQuery("SELECT VERSION(), (SELECT amount FROM t_ping WHERE id = 1)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).startsWith("8.0");
                assertThat(rs.getLong(2)).isEqualTo(1999L);
            }
        }
    }

    @Test
    void mysql_isSameInstanceAcrossCalls() {
        // Singleton: repeated lookups return the same container, never started twice
        assertThat(SharedContainers.mysql()).isSameAs(SharedContainers.mysql());
    }

    @Test
    void redis_canSetAndGet() {
        GenericContainer<?> redis = SharedContainers.redis();
        RedisClient client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().set("mh:ping", "pong");
            assertThat(conn.sync().get("mh:ping")).isEqualTo("pong");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void rocketmq_canSendMessage() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("mh-it-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        // The VIP channel connects to listenPort-2, which the container doesn't map, so turn it off
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(10_000);
        producer.start();
        try {
            SendResult result = sendWithRetry(producer,
                    new Message("MH_IT_PING", "ping".getBytes(StandardCharsets.UTF_8)));
            assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        } finally {
            producer.shutdown();
        }
    }

    /**
     * After the Broker logs boot success, it may take a few more seconds before its route is visible on the NameServer,
     * so the first send occasionally fails with No route info; retry briefly (up to ~30 seconds).
     */
    private static SendResult sendWithRetry(DefaultMQProducer producer, Message msg) throws Exception {
        Exception last = null;
        for (int i = 0; i < 15; i++) {
            try {
                return producer.send(msg);
            } catch (Exception e) {
                last = e;
                Thread.sleep(2_000);
            }
        }
        throw last;
    }
}
