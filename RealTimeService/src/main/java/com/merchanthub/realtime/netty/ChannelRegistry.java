package com.merchanthub.realtime.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This node's connection table: receiver -> all its connections on this node (several staff of one merchant / several devices of one buyer).
 * The original IM's ChannelManager was a static Map holding one connection per userId; here it's an instance with multiple connections, and several nodes can run in one JVM (tests).
 */
@Component
public class ChannelRegistry {

    public static final AttributeKey<String> RECEIVER = AttributeKey.valueOf("receiverKey");

    private final Map<String, Set<Channel>> byReceiver = new ConcurrentHashMap<>();

    /** @return true = this is the receiver's first connection on this node */
    public boolean add(String receiverKey, Channel ch) {
        ch.attr(RECEIVER).set(receiverKey);
        boolean[] first = {false};
        byReceiver.compute(receiverKey, (k, set) -> {
            if (set == null) {
                set = ConcurrentHashMap.newKeySet();
                first[0] = true;
            }
            set.add(ch);
            return set;
        });
        return first[0];
    }

    /** @return the receiver if this was its last connection on this node (the caller then unregisters the route); otherwise null */
    public String remove(Channel ch) {
        String key = ch.attr(RECEIVER).get();
        if (key == null) {
            return null;
        }
        boolean[] last = {false};
        byReceiver.computeIfPresent(key, (k, set) -> {
            set.remove(ch);
            if (set.isEmpty()) {
                last[0] = true;
                return null;
            }
            return set;
        });
        return last[0] ? key : null;
    }

    public List<Channel> channelsOf(String receiverKey) {
        Set<Channel> set = byReceiver.get(receiverKey);
        return set == null ? List.of() : set.stream().filter(Channel::isActive).toList();
    }
}
