package com.merchanthub.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OrderConfig {

    /** 统一用 UTC 时钟（与数据库会话时区一致）；单测里可以换成固定时钟 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
