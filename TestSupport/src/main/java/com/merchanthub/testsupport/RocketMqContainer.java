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
