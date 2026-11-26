package com.merchanthub.common.tenant.feign;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Cloud OpenFeign applies any RequestInterceptor Bean in the application context to all FeignClients */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class TenantFeignConfig {

    @Bean
    public TenantFeignInterceptor tenantFeignInterceptor() {
        return new TenantFeignInterceptor();
    }
}
