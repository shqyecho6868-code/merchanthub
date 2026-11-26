package com.merchanthub.realtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "merchanthub.realtime")
public class RealtimeProperties {

    /** Node ID; when empty, rt-{IP}-{port} is generated automatically (see RealtimeConfig) */
    private String nodeId;
    /** WebSocket port; 0 = random (for tests) */
    private int nettyPort = 9101;
    /** Route TTL: renewed by heartbeat; after a node crashes, its route disappears within at most this long */
    private Duration routeTtl = Duration.ofSeconds(90);
    /** Read idle timeout: disconnect if no frame (including ping) is received for this long */
    private Duration idleTimeout = Duration.ofSeconds(90);
}
