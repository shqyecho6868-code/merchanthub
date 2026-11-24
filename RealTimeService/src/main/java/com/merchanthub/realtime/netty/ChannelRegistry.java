package com.merchanthub.realtime.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本节点的连接表：接收方 → 本节点上它的所有连接（同一商家多个员工 / 同一买家多个设备）。
 * 原 IM 的 ChannelManager 是静态 Map，一个 userId 只存一条连接；这里改成实例 + 多连接，并且同一个 JVM 里可以起多个节点（测试）。
 */
@Component
public class ChannelRegistry {

    public static final AttributeKey<String> RECEIVER = AttributeKey.valueOf("receiverKey");

    private final Map<String, Set<Channel>> byReceiver = new ConcurrentHashMap<>();

    /** @return true = 这是该接收方在本节点的第一条连接 */
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

    /** @return 如果这是该接收方在本节点的最后一条连接，返回接收方（调用方据此注销路由）；否则 null */
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
