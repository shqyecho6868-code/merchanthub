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
 * Dispatch one push (the core of realtime push): store as pending -> look up the route table -> write directly on this node / forward via MQ to other nodes / leave it in the table if offline.
 * <p>
 * Example: merchant 7 is connected to node A and node B consumes the order event: B looks up rt:route:merchant:7 and gets {A} -> sends a REALTIME_PUSH message with Tag = A
 * -> only A's dedicated consumer group subscribes to Tag A -> A writes to merchant 7's connection and marks it delivered.
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
        store.save(msg);   // store first: if any later step fails (forward failure, target node crash), the message is still there for redelivery on coming online
        Set<String> nodes = routes.nodesOf(msg.receiverKey());
        if (nodes.isEmpty()) {
            log.info("[offline] Receiver offline, message kept for redelivery on coming online receiverKey={} msgId={}", msg.receiverKey(), msg.msgId());
            return;
        }
        for (String node : nodes) {
            if (node.equals(self.value())) {
                local.deliver(msg);   // on this node: skip MQ
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
            log.info("[forward] {} -> node {} receiverKey={} msgId={}", self, node, msg.receiverKey(), msg.msgId());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // No retry on forward failure: the message is already "pending" and will be redelivered when the receiver comes online
            log.warn("Failed to forward to node {}, kept for redelivery on coming online receiverKey={} msgId={}", node, msg.receiverKey(), msg.msgId(), e);
        }
    }
}
