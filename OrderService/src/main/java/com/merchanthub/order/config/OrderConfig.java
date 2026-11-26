package com.merchanthub.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OrderConfig {

    /** Use a UTC clock everywhere (matches the DB session time zone); unit tests can swap in a fixed clock */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
