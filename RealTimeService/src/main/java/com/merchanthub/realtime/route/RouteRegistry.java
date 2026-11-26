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
 * Push route table (E1): which nodes a receiver is connected to.
 * <pre>
 *   key   = rt:route:{receiverKey}          e.g. rt:route:merchant:7
 *   type  = Hash, field = node ID, value = registration time
 *   expiry = per-field TTL (HEXPIRE in Redis 7.4), renewed by heartbeat
 * </pre>
 * Why a Hash + per-field expiry instead of a String (rt:route:merchant:7 = nodeA):
 * <ul>
 *   <li>A merchant has several staff who may be connected to nodes A and C respectively, so one receiver must map to multiple nodes</li>
 *   <li>Each node only adds, removes and renews <b>its own field</b>: when A disconnects it HDELs itself and can't accidentally delete the route C just created (a String would need a "delete only if value is me" Lua script)</li>
 *   <li>A crashes: nobody renews A's field, so it disappears when its TTL is up, C's is unaffected; once all fields are gone the key is deleted automatically</li>
 * </ul>
 * Trade-off: requires Redis >= 7.4 (docker-compose and tests both use 7.4).
 */
@Slf4j
@Component
public class RouteRegistry {

    public static final String PREFIX = "rt:route:";

    /** HSET overwriting a field clears its TTL, so it must always be followed by HEXPIRE; both steps go in Lua to stay atomic */
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

    /** Connection established: record "this receiver is on this node", with a TTL */
    public void register(String receiverKey) {
        upsert(receiverKey);
        log.info("路由注册 {} → {}（TTL {}s）", receiverKey, nodeId, ttlSeconds);
    }

    /** Heartbeat renewal: same operation as registration (value refreshed to now, TTL restarted) */
    public void renew(String receiverKey) {
        upsert(receiverKey);
    }

    /** The receiver's last connection on this node closed: delete only our own field */
    public void unregister(String receiverKey) {
        redis.opsForHash().delete(PREFIX + receiverKey, nodeId.value());
        log.info("路由注销 {} ✕ {}", receiverKey, nodeId);
    }

    /** Nodes the receiver is currently connected to; empty = offline */
    public Set<String> nodesOf(String receiverKey) {
        return redis.opsForHash().keys(PREFIX + receiverKey).stream().map(Object::toString).collect(Collectors.toSet());
    }

    private void upsert(String receiverKey) {
        redis.execute(UPSERT, List.of(PREFIX + receiverKey), nodeId.value(),
                String.valueOf(System.currentTimeMillis()), String.valueOf(ttlSeconds));
    }
}
