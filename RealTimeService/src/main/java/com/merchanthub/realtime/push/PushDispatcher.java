package com.merchanthub.realtime.push;

import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.realtime.config.NodeId;
import com.merchanthub.realtime.route.RouteRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 分发一条推送（E1 的核心）：先存待送达 → 查路由表 → 本节点直接写连接 / 别的节点经 MQ 转发 / 不在线就留在表里。
 * <p>
 * 例：商家 7 连在节点 A，订单事件被节点 B 消费到：B 查 rt:route:merchant:7 得到 {A} → 发一条 Tag = A 的 REALTIME_PUSH 消息
 * → 只有 A 的专属消费组订阅了 Tag A → A 写进商家 7 的连接、标记已送达。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher {

    private final RouteRegistry routes;
    private final OfflineMessageStore store;
    private final LocalDelivery local;
    private final DefaultMQProducer producer;
    private final NodeId self;

    public void dispatch(PushMessage msg) {
        store.save(msg);   // 先存：后面任何一步失败（转发失败、目标节点宕机），消息都还在，上线补发
        Set<String> nodes = routes.nodesOf(msg.receiverKey());
        if (nodes.isEmpty()) {
            log.info("【离线】接收方不在线，消息留待上线补发 receiverKey={} msgId={}", msg.receiverKey(), msg.msgId());
            return;
        }
        for (String node : nodes) {
            if (node.equals(self.value())) {
                local.deliver(msg);   // 就在本节点：不绕 MQ
            } else {
                forward(node, msg);
            }
        }
    }

    private void forward(String node, PushMessage msg) {
        try {
            Message m = new Message(PushTopics.PUSH_TOPIC, node, store.toJson(msg).getBytes(StandardCharsets.UTF_8));
            m.setKeys(msg.msgId());
            producer.send(TenantMessages.stamp(m, msg.tenantId()));
            log.info("【转发】{} → 节点 {} receiverKey={} msgId={}", self, node, msg.receiverKey(), msg.msgId());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 转发失败不重试：消息已经是"待送达"，接收方下次上线会补发
            log.warn("转发到节点 {} 失败，留待上线补发 receiverKey={} msgId={}", node, msg.receiverKey(), msg.msgId(), e);
        }
    }
}
