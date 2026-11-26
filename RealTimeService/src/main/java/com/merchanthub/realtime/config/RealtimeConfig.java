package com.merchanthub.realtime.config;

import com.merchanthub.realtime.push.PushTopics;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

@Configuration
public class RealtimeConfig {

    /**
     * Node ID: explicit config wins; otherwise rt-{host IP}-{Netty port} (multiple nodes on one machine have different ports, hence different IDs).
     * If the port is 0 (random) and no ID is configured, append a random suffix -- this only happens in tests.
     * In production prefer a fixed ID (e.g. the Pod name of a K8s StatefulSet): a restarted node reuses the same consumer group, so messages forwarded to it while it was down aren't dropped
     */
    @Bean
    public NodeId nodeId(RealtimeProperties props) {
        if (props.getNodeId() != null && !props.getNodeId().isBlank()) {
            return new NodeId(props.getNodeId());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String suffix = props.getNettyPort() == 0 ? UUID.randomUUID().toString().substring(0, 8) : String.valueOf(props.getNettyPort());
        return new NodeId("rt-" + host + "-" + suffix);
    }

    /** Plain producer for inter-node forwarding */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer realtimePushProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
                                                  NodeId nodeId) {
        DefaultMQProducer producer = new DefaultMQProducer("realtime-push-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        // When two nodes run in one JVM (tests), producer instance names must differ, otherwise they share one client instance
        producer.setInstanceName("realtime-" + nodeId.value());
        producer.setTopics(List.of(PushTopics.PUSH_TOPIC));
        return producer;
    }
}
