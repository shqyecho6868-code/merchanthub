package com.merchanthub.common.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用上下文里的 RequestInterceptor Bean 会被 Spring Cloud OpenFeign 应用到所有 FeignClient（同 TenantFeignConfig） */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class TraceFeignConfig {

    @Bean
    public TraceFeignInterceptor traceFeignInterceptor() {
        return new TraceFeignInterceptor();
    }
}
