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
     * 节点 ID：显式配置优先；否则 rt-{本机 IP}-{Netty 端口}（同一台机器起多个节点时端口不同，ID 也不同）。
     * 端口配成 0（随机）又没配 ID 时拼一个随机后缀 —— 只在测试里出现。
     * 生产建议固定 ID（如 K8s StatefulSet 的 Pod 名）：节点重启后沿用同一个消费组，宕机期间转给它的消息不会被扔掉
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

    /** 节点间转发用的普通生产者 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer realtimePushProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
                                                  NodeId nodeId) {
        DefaultMQProducer producer = new DefaultMQProducer("realtime-push-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        // 同一个 JVM 里起两个节点（测试）时，生产者实例名必须不同，否则共用一个客户端实例
        producer.setInstanceName("realtime-" + nodeId.value());
        producer.setTopics(List.of(PushTopics.PUSH_TOPIC));
        return producer;
    }
}
