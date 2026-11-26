package com.merchanthub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Only scan the gateway's own package: the gateway only uses static utilities from Common (ErrorCode etc.) and the auto-configured JwtCodec,
 * and needs none of Common's Servlet-oriented beans (global exception handler, logging aspect, MyBatis config).
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class}, scanBasePackages = {"com.merchanthub.gateway"})
public class GateWayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateWayApplication.class, args);
    }

}