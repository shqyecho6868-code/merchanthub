package com.merchanthub.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spring Boot 集成测试基类：把共享容器的地址注入成 Spring 配置。
 * <p>
 * 用法：{@code @SpringBootTest class XxxIT extends IntegrationTestBase}
 * <p>
 * 这里注册的都是 Supplier，Spring 真正读取某个属性时才会调用，进而触发对应容器启动。
 * 所以服务里没有 Redis 依赖时，spring.data.redis.* 不会被读取，Redis 容器也不会启动。
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

        // 读到这个属性时才会启动 RocketMQ 容器；不用 MQ 的服务不受影响
        registry.add("merchanthub.rocketmq.namesrv-addr", () -> SharedContainers.rocketmq().getNamesrvAddr());

        // 测试不连 Nacos：只测本服务，跨服务调用用 Mock 或 WireMock 替代
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");

        // E3：测试不注册 XXL-Job 执行器（不起调度中心，任务方法由测试直接调用）。
        // 必须放在这里而不是 test 的 application.properties：测试也激活 dev profile，
        // application-dev.yml 里的 enabled=true 优先级高于非 profile 的配置文件，会把它盖掉
        registry.add("merchanthub.job.xxl.enabled", () -> "false");
    }
}
