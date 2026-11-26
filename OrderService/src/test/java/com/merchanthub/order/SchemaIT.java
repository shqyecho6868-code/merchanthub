package com.merchanthub.order;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: the context starts, Flyway creates the tables, and the snowflake ID generator has claimed a slot from Redis */
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
                .contains("orders", "order_item", "trade_snapshot", "order_status_log", "order_outbox",
                        "consumed_event", "pay_anomaly", "recon_exception", "order_outbox_archive", "flyway_schema_history")
                .doesNotContain("merchant_order_notice");   // moved to the realtime service
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
