package com.merchanthub.product;

import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 冒烟：Spring 上下文能起来，schema.sql 在真实 MySQL 8 上执行成功，关键唯一约束存在 */
@SpringBootTest
class SchemaIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tablesAndUniqueKeysExist() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class))
                .contains("product", "sku", "inventory", "inventory_reservation");

        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'inventory_reservation' AND index_name = 'uk_order_sku' ORDER BY seq_in_index",
                String.class))
                .containsExactly("order_no", "sku_id");
    }
}
