package com.merchanthub.realtime;

import com.merchanthub.realtime.config.RealtimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * realtime：订单事件实时推送（E1）。商家收到新订单提醒，买家收到订单状态变化。
 * 多节点部署：用户连在哪台节点记在 Redis 路由表，订单事件由任意节点消费后按路由表转发到用户所在节点；
 * 每条推送先落库（offline_message）再投递，送达才标记，没送达的上线后补发。
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.realtime", "com.merchanthub.common"})
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealTimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeServiceApplication.class, args);
    }
}
