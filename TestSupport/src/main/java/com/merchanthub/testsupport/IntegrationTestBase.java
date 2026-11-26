package com.merchanthub.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for Spring Boot integration tests: injects the shared containers' addresses as Spring properties.
 * <p>
 * Usage: {@code @SpringBootTest class XxxIT extends IntegrationTestBase}
 * <p>
 * Everything registered here is a Supplier, invoked only when Spring actually reads that property, which in turn starts the matching container.
 * So when a service has no Redis dependency, spring.data.redis.* is never read and the Redis container never starts.
 */
public abstract class IntegrationTestBase {

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedContainers.mysql().getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> SharedContainers.mysql().getUsername());
        registry.add("spring.datasource.password", () -> SharedContainers.mysql().getPassword());

        registry.add("spring.data.redis.host", () -> SharedContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> SharedContainers.redis().getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        // The RocketMQ container starts only when this property is read; services that don't use MQ are unaffected
        registry.add("merchanthub.rocketmq.namesrv-addr", () -> SharedContainers.rocketmq().getNamesrvAddr());

        // Tests don't connect to Nacos: only this service is tested; cross-service calls are replaced with Mock or WireMock
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");

        // Tests don't register an XXL-Job executor (no admin server; job methods are called directly by tests).
        // It must live here rather than in the test application.properties: tests also activate the dev profile,
        // and enabled=true in application-dev.yml takes precedence over non-profile config files and would override it
        registry.add("merchanthub.job.xxl.enabled", () -> "false");
    }
}
