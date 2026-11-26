package com.merchanthub.common.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Cloud OpenFeign applies any RequestInterceptor Bean in the application context to all FeignClients (same as TenantFeignConfig) */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class TraceFeignConfig {

    @Bean
    public TraceFeignInterceptor traceFeignInterceptor() {
        return new TraceFeignInterceptor();
    }
}
