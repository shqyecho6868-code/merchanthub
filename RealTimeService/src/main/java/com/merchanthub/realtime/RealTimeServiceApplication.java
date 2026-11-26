package com.merchanthub.realtime;

import com.merchanthub.realtime.config.RealtimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * realtime: real-time push of order events. Merchants get new-order alerts, buyers get order status changes.
 * Multi-node deployment: which node a user is connected to is recorded in a Redis route table; any node consumes an order event and forwards it to the user's node via the route table;
 * every push is persisted first (offline_message) and then delivered, marked only once delivered; undelivered ones are redelivered when the user comes online.
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.realtime", "com.merchanthub.common"})
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealTimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeServiceApplication.class, args);
    }
}
