package com.merchanthub.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Singleton containers: within one JVM (= one failsafe run of one Maven module), all test classes share one set of containers.
 * <p>
 * Difference from the {@code @Container static} in a typical Testcontainers example: that style stops the containers after each test class and restarts them for the next,
 * and MySQL takes about 10 seconds each time, so it gets slow with many test classes. Here there's no @Container: a container starts on first use
 * and lives until the JVM exits; Testcontainers' Ryuk container handles final cleanup.
 * <p>
 * Each container sits in its own Holder (the lazy-initialization holder idiom): only containers actually used are started,
 * e.g. product-inventory has no Redis, so no Redis container is started for nothing.
 * <p>
 * Image versions are kept in sync with docker-compose.yml.
 */
public final class SharedContainers {

    private SharedContainers() {
    }

    public static MySQLContainer<?> mysql() {
        return MysqlHolder.INSTANCE;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    public static RocketMqContainer rocketmq() {
        return RocketMqHolder.INSTANCE;
    }

    private static final class MysqlHolder {
        static final MySQLContainer<?> INSTANCE = start(new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("mh_test")
                // Concurrency tests open hundreds of connections; the default 151 is enough. Timezone matches the app: UTC
                .withCommand("--default-time-zone=+00:00"));
    }

    private static final class RedisHolder {
        static final GenericContainer<?> INSTANCE = start(new GenericContainer<>("redis:7.4").withExposedPorts(6379));
    }

    private static final class RocketMqHolder {
        static final RocketMqContainer INSTANCE = start(new RocketMqContainer());
    }

    private static <T extends GenericContainer<?>> T start(T container) {
        container.start();
        return container;
    }
}
