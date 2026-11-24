package com.merchanthub.realtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "merchanthub.realtime")
public class RealtimeProperties {

    /** 节点 ID；为空时自动生成 rt-{IP}-{端口}（见 RealtimeConfig） */
    private String nodeId;
    /** WebSocket 端口；0 = 随机（测试用） */
    private int nettyPort = 9101;
    /** 路由过期时间：心跳续期，节点宕机后最多这么久路由消失 */
    private Duration routeTtl = Duration.ofSeconds(90);
    /** 读空闲超时：这么久没收到任何帧（包括 ping）就断开 */
    private Duration idleTimeout = Duration.ofSeconds(90);
}
