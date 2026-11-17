package com.merchanthub.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.merchanthub.realtime", "com.merchanthub.common"})
@EnableFeignClients(basePackages = "com.merchanthub.realtime.client")
public class RealTimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeServiceApplication.class, args);
    }

}
