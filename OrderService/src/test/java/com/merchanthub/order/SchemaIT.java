package com.merchanthub.order;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 冒烟：上下文能起来，表建成功，雪花发号器已从 Redis 领到槽位 */
@SpringBootTest
class SchemaIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @Test
    void tablesExist() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class))
                .contains("orders", "order_item", "trade_snapshot", "order_status_log");
    }

    @Test
    void snowflakeGeneratorIsReady() {
        assertThat(idGenerator.getSlot()).isBetween(0, 1023);
        assertThat(idGenerator.nextId()).isPositive();
    }

    @Test
    void sessionTimeZoneIsUtc() {
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isIn("+00:00", "UTC");
    }
}
