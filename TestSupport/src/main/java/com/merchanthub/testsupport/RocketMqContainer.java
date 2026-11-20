package com.merchanthub.testsupport;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * 单容器跑 NameServer + Broker。
 * <p>
 * 难点：生产者先问 NameServer 要路由，拿到的是 Broker 自己注册的 “brokerIP1:listenPort”，再直连这个地址。
 * Testcontainers 默认把容器端口映射到宿主机的随机端口，Broker 并不知道这个随机端口，注册的地址宿主机连不上。
 * 做法：先在宿主机找一个空闲端口 P，让 Broker 在容器内也监听 P，并固定映射 P→P，brokerIP1 设为 Docker 主机地址。
 * 这样 Broker 注册的地址在宿主机上就是可达的。（和 docker-compose 里用 host.docker.internal 是同一个问题，见 A2 笔记。）
 * NameServer 端口照常随机映射即可，因为它的地址是我们主动传给客户端的。
 */
public class RocketMqContainer extends GenericContainer<RocketMqContainer> {

    private static final int NAMESRV_PORT = 9876;

    public RocketMqContainer() {
        this(findFreePort());
    }

    public RocketMqContainer(int brokerPort) {
        super("apache/rocketmq:5.3.1");
        String brokerConf = String.join("\n",
                "brokerClusterName=DefaultCluster",
                "brokerName=broker-a",
                "brokerId=0",
                "brokerRole=ASYNC_MASTER",
                "flushDiskType=ASYNC_FLUSH",
                "autoCreateTopicEnable=true",
                // 事务消息回查调快（默认几十秒一轮），让回查类测试几秒内出结果：
                // transactionTimeOut 是半消息"多老才会被回查"的下限，也是免回查时间的最小值
                "transactionCheckInterval=1000",
                "transactionTimeOut=3000",
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

    /**
     * 提前建 Topic（和生产环境"关闭自动建 Topic、提前规划"的做法一致）。
     * 依赖自动建 Topic 时，刚启动的 Broker 第一次发送常报 No route info，测试会偶发失败。
     */
    public void createTopic(String topic) {
        // Broker 打出 boot success 后，到它出现在 NameServer 的集群信息里还有一段延迟，
        // 这期间 mqadmin 报 "Make sure the specified clusterName exists"，所以重试（最多约 60 秒）
        String last = "";
        for (int i = 0; i < 30; i++) {
            try {
                ExecResult r = execInContainer("sh", "mqadmin", "updateTopic",
                        "-n", "localhost:" + NAMESRV_PORT, "-c", "DefaultCluster", "-t", topic);
                if (r.getExitCode() == 0 && r.getStdout().contains("success")) {
                    return;
                }
                last = r.getStdout() + r.getStderr();
                Thread.sleep(2_000);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("创建 Topic 失败: " + last);
    }

    public String getNamesrvAddr() {
        return getHost() + ":" + getMappedPort(NAMESRV_PORT);
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
