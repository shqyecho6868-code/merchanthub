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
 * 共享容器的冒烟测试（A2+ 放在 Common，B1 随容器一起抽到 TestSupport）：
 * 证明 MySQL / Redis / RocketMQ 三个单例容器在本机和 CI 上都能起来、能连上。不含业务逻辑。
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
        // 单例：多次获取是同一个容器，不会重复启动
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
        // VIP 通道会连 listenPort-2，容器没映射那个端口，关掉
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
     * Broker 打出 boot success 后，到 NameServer 上路由可见之间可能还有几秒，
     * 第一次发送偶尔会报 No route info，所以短暂重试（最多约 30 秒）。
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
