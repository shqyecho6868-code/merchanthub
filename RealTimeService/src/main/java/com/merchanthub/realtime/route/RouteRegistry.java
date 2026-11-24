package com.merchanthub.realtime.route;

import com.merchanthub.realtime.config.NodeId;
import com.merchanthub.realtime.config.RealtimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推送路由表（E1）：接收方连在哪些节点。
 * <pre>
 *   key   = rt:route:{receiverKey}          例：rt:route:merchant:7
 *   type  = Hash，field = 节点 ID，value = 注册时间
 *   过期  = 每个 field 单独 TTL（Redis 7.4 的 HEXPIRE），心跳续期
 * </pre>
 * 为什么是 Hash + 字段级过期，而不是 String（rt:route:merchant:7 = nodeA）：
 * <ul>
 *   <li>商家有多个员工，可能分别连在 A、C 两台节点，同一个接收方要能对应多个节点</li>
 *   <li>每个节点只增删、续期<b>自己那个 field</b>：A 断开时 HDEL 自己，不会误删 C 刚建的路由（String 方案要"值等于自己才删"的 Lua）</li>
 *   <li>A 宕机：没人给 A 的 field 续期，TTL 到了这个 field 自动消失，C 的不受影响；所有 field 都没了 key 也自动删除</li>
 * </ul>
 * 代价：依赖 Redis ≥ 7.4（docker-compose 和测试都是 7.4）。
 */
@Slf4j
@Component
public class RouteRegistry {

    public static final String PREFIX = "rt:route:";

    /** HSET 覆盖一个 field 会清掉它的 TTL，所以每次都要紧跟一次 HEXPIRE；两步放进 Lua 保证原子 */
    private static final DefaultRedisScript<Long> UPSERT = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) "
                    + "return redis.call('HEXPIRE', KEYS[1], ARGV[3], 'FIELDS', 1, ARGV[1])[1]", Long.class);

    private final StringRedisTemplate redis;
    private final NodeId nodeId;
    private final long ttlSeconds;

    public RouteRegistry(StringRedisTemplate redis, NodeId nodeId, RealtimeProperties props) {
        this.redis = redis;
        this.nodeId = nodeId;
        this.ttlSeconds = props.getRouteTtl().toSeconds();
    }

    /** 连接建立：记下"这个接收方在本节点"，带 TTL */
    public void register(String receiverKey) {
        upsert(receiverKey);
        log.info("路由注册 {} → {}（TTL {}s）", receiverKey, nodeId, ttlSeconds);
    }

    /** 心跳续期：和注册是同一个操作（值刷新成当前时间、TTL 重新计时） */
    public void renew(String receiverKey) {
        upsert(receiverKey);
    }

    /** 本节点上这个接收方的最后一条连接断开：只删自己的 field */
    public void unregister(String receiverKey) {
        redis.opsForHash().delete(PREFIX + receiverKey, nodeId.value());
        log.info("路由注销 {} ✕ {}", receiverKey, nodeId);
    }

    /** 接收方当前连在哪些节点；空 = 不在线 */
    public Set<String> nodesOf(String receiverKey) {
        return redis.opsForHash().keys(PREFIX + receiverKey).stream().map(Object::toString).collect(Collectors.toSet());
    }

    private void upsert(String receiverKey) {
        redis.execute(UPSERT, List.of(PREFIX + receiverKey), nodeId.value(),
                String.valueOf(System.currentTimeMillis()), String.valueOf(ttlSeconds));
    }
}
