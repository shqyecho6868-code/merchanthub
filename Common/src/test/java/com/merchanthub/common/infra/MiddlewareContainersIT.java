package com.merchanthub.common.infra;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers 集成测试示例（A2+）：证明 MySQL / Redis / RocketMQ 三个容器在本机和 CI 上都能起来、能连上。
 * <p>
 * 镜像版本与 docker-compose.yml 保持一致，保证“测试环境 = 开发环境”。
 * 命名以 IT 结尾 → 由 failsafe 在 integration-test 阶段执行（mvn verify），需要 Docker。
 * 这里只验证基础设施连通，不含业务逻辑；B1 起各服务的集成测试按这个写法起容器。
 */
@Testcontainers
class MiddlewareContainersIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("merchanthub");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379);

    @Container
    static final RocketMqContainer ROCKETMQ = new RocketMqContainer(findFreePort());

    @Test
    void mysql_canCreateTableAndQuery() throws Exception {
        try (Connection conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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
    void redis_canSetAndGet() {
        RedisClient client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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
        producer.setNamesrvAddr(ROCKETMQ.getNamesrvAddr());
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

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 单容器跑 NameServer + Broker。
     * <p>
     * 难点：生产者先问 NameServer 要路由，拿到的是 Broker 自己注册的 “brokerIP1:listenPort”，再直连这个地址。
     * Testcontainers 默认把容器端口映射到宿主机的随机端口，Broker 并不知道这个随机端口，注册的地址宿主机连不上。
     * 做法：先在宿主机找一个空闲端口 P，让 Broker 在容器内也监听 P，并固定映射 P→P，brokerIP1 设为 Docker 主机地址。
     * 这样 Broker 注册的地址在宿主机上就是可达的。（和 docker-compose 里用 host.docker.internal 是同一个问题，见 A2 笔记。）
     * NameServer 端口照常随机映射即可，因为它的地址是我们主动传给客户端的。
     */
    static class RocketMqContainer extends GenericContainer<RocketMqContainer> {

        private static final int NAMESRV_PORT = 9876;

        RocketMqContainer(int brokerPort) {
            super("apache/rocketmq:5.3.1");
            String brokerConf = String.join("\n",
                    "brokerClusterName=DefaultCluster",
                    "brokerName=broker-a",
                    "brokerId=0",
                    "brokerRole=ASYNC_MASTER",
                    "flushDiskType=ASYNC_FLUSH",
                    "autoCreateTopicEnable=true",
                    "listenPort=" + brokerPort,
                    "brokerIP1=" + DockerClientFactory.instance().dockerHostIpAddress(),
                    "");
            withExposedPorts(NAMESRV_PORT);
            addFixedExposedPort(brokerPort, brokerPort);
            withCopyToContainer(Transferable.of(brokerConf), "/home/rocketmq/broker-it.conf");
            // 默认堆按 GB 申请，CI 机器和本机都吃不消，调小
            withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m -Xmn256m");
            withCommand("sh", "-c",
                    "sh mqnamesrv & sh mqbroker -n localhost:" + NAMESRV_PORT + " -c /home/rocketmq/broker-it.conf");
            waitingFor(Wait.forLogMessage(".*boot success.*", 2).withStartupTimeout(Duration.ofMinutes(3)));
        }

        String getNamesrvAddr() {
            return getHost() + ":" + getMappedPort(NAMESRV_PORT);
        }
    }
}
