package com.merchanthub.payment;

import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.merchanthub.payment.client.OrderClient;

import static org.assertj.core.api.Assertions.assertThat;

/** 冒烟：Flyway 在真实 MySQL 8 上建出 payment 表，幂等依赖的两个唯一键存在 */
@SpringBootTest
class SchemaIT extends IntegrationTestBase {

    @MockitoBean
    private OrderClient orderClient;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void uniqueKeysExist() {
        assertThat(jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'payment' AND non_unique = 0", String.class))
                .contains("uk_pay_no", "uk_channel_trade_no");
    }
}
