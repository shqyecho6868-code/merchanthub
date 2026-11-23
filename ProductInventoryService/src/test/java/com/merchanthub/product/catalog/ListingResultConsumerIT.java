package com.merchanthub.product.catalog;

import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.product.ProductItSupport;
import com.merchanthub.product.catalog.mq.ListingResultEvent;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 端到端（D1）：同一条"上架通过"消息投递 3 次 → 消费者 → 商品只从 1 改成 2 一次、只有一条流水。
 * 另一条消息属性里的商家是 8（别的商家），改不了商家 7 的商品。
 */
@SpringBootTest(properties = "merchanthub.product.listing-result-consumer.enabled=true")
class ListingResultConsumerIT extends ProductItSupport {

    private static DefaultMQProducer producer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        SharedContainers.rocketmq().createTopic(ListingResultEvent.TOPIC);
        producer = new DefaultMQProducer("it-listing-result-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stop() {
        producer.shutdown();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE product");
        jdbc.execute("TRUNCATE TABLE product_status_log");
    }

    private long inReview(long tenant) {
        jdbc.update("INSERT INTO product (tenant_id, name, status, audit_round, audit_submitted_at) VALUES (?, '旗舰手机', 1, 1, NOW(3))", tenant);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void send(long tenantProp, long productId, String result, int times) throws Exception {
        String json = "{\"eventId\":" + System.nanoTime() + ",\"tenantId\":7,\"productId\":" + productId
                + ",\"round\":1,\"result\":\"" + result + "\",\"rejectReason\":null,\"processInstanceId\":\"pid-mq\"}";
        for (int i = 0; i < times; i++) {
            producer.send(TenantMessages.stamp(new Message(ListingResultEvent.TOPIC, ListingResultEvent.TAG,
                    json.getBytes(StandardCharsets.UTF_8)), tenantProp));
        }
    }

    private int status(long id) {
        return jdbc.queryForObject("SELECT status FROM product WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("重复投递 3 次：状态只改一次（1 → 2），流水 1 条，来源 MQ")
    void duplicated_appliedOnce() throws Exception {
        long id = inReview(7);
        send(7, id, "APPROVED", 3);

        await().atMost(Duration.ofSeconds(60)).until(() -> status(id) == 2);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_status_log WHERE product_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM product_status_log WHERE product_id = ?", String.class, id)).isEqualTo("MQ");
        assertThat(jdbc.queryForObject("SELECT audit_instance_id FROM product WHERE id = ?", String.class, id)).isEqualTo("pid-mq");
    }

    @Test
    @DisplayName("消息属性里是商家 8：CAS 带 tenant_id = 8，改不了商家 7 的商品")
    void otherTenantMessage_cannotChange() throws Exception {
        long id = inReview(7);
        long marker = inReview(7);
        send(8, id, "APPROVED", 1);
        send(7, marker, "REJECTED", 1);   // 用同一个消费者处理完它，说明前一条也已经处理过了（同一队列顺序不保证，所以多等一会儿）

        await().atMost(Duration.ofSeconds(60)).until(() -> status(marker) == 4);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(status(id)).isEqualTo(1);
    }
}
