package com.merchanthub.testsupport;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * Runs NameServer + Broker in a single container.
 * <p>
 * The tricky part: a producer first asks the NameServer for routes, receives the "brokerIP1:listenPort" the Broker registered itself with, then connects to it directly.
 * Testcontainers maps container ports to random host ports by default; the Broker doesn't know that random port, so the registered address is unreachable from the host.
 * Fix: find a free port P on the host, have the Broker listen on P inside the container too, map P->P fixed, and set brokerIP1 to the Docker host address.
 * That way the address the Broker registers is reachable from the host. (Same problem as using host.docker.internal in docker-compose.)
 * The NameServer port can be randomly mapped as usual, since we hand its address to clients ourselves.
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
                // Speed up transactional message checkback (default is tens of seconds per round) so checkback tests finish within seconds:
                // transactionTimeOut is the lower bound on "how old a half message must be before it's checked back", and also the minimum check-immunity time
                "transactionCheckInterval=1000",
                "transactionTimeOut=3000",
                "listenPort=" + brokerPort,
                "brokerIP1=" + DockerClientFactory.instance().dockerHostIpAddress(),
                "");
        withExposedPorts(NAMESRV_PORT);
        addFixedExposedPort(brokerPort, brokerPort);
        withCopyToContainer(Transferable.of(brokerConf), "/home/rocketmq/broker-it.conf");
        // The default heap is sized in GB, too much for both CI machines and local; shrink it
        withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m -Xmn256m");
        withCommand("sh", "-c",
                "sh mqnamesrv & sh mqbroker -n localhost:" + NAMESRV_PORT + " -c /home/rocketmq/broker-it.conf");
        waitingFor(Wait.forLogMessage(".*boot success.*", 2).withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * Create Topics up front (consistent with production practice of "disable auto topic creation, plan topics in advance").
     * With auto-creation, a freshly started Broker often fails the first send with No route info, making tests flaky.
     */
    public void createTopic(String topic) {
        // After the Broker logs boot success, there's still a delay before it appears in the NameServer's cluster info;
        // meanwhile mqadmin reports "Make sure the specified clusterName exists", so retry (up to ~60 seconds)
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
        throw new IllegalStateException("Failed to create Topic: " + last);
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
