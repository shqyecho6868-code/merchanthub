package com.merchanthub.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 单例容器：同一个 JVM（= 同一个 Maven 模块的一次 failsafe 运行）里，所有测试类共用一组容器。
 * <p>
 * 和 A2+ 示例里 {@code @Container static} 的区别：那种写法每个测试类结束就停容器，下一个类再起一遍，
 * MySQL 一次 10 秒左右，测试类一多就很慢。这里不用 @Container，容器第一次被用到时启动，
 * 一直活到 JVM 退出，由 Testcontainers 的 Ryuk 容器负责最终清理。
 * <p>
 * 每个容器放在自己的 Holder 里（按需初始化的 holder 惯用法）：只有真正用到的容器才会启动，
 * 比如 product-inventory 没有 Redis，就不会白起一个 Redis 容器。
 * <p>
 * 镜像版本与 docker-compose.yml 保持一致。
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
                // 并发测试会开上百个连接，默认 151 够用；时区与应用一致用 UTC
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
