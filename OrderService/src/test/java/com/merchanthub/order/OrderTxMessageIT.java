package com.merchanthub.order;

import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.service.OrderPersistService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 方案一（事务消息）正常路径：本地事务成功 → 消息可见；本地事务失败 → 消息永不可见。
 */
@SpringBootTest(properties = "merchanthub.order.event-mode=TX_MESSAGE")
class OrderTxMessageIT extends OrderItSupport {

    private static OrderEventObserver observer;

    @MockitoSpyBean
    private OrderPersistService persistService;

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    @Test
    void localTxCommitted_messageBecomesVisible_withOurEventId() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 2)).getOrderNo());
        assertThat(countOrders(orderNo)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
        MessageExt m = observer.received(orderNo).get(0);
        assertThat(m.getTags()).isEqualTo(OrderEventTopics.TAG_ORDER_CREATED);
        String eventId = m.getUserProperty(OrderEventTopics.PROP_EVENT_ID);
        assertThat(eventId).isNotBlank().isNotEqualTo(m.getMsgId());
        assertThat(observer.body(m)).contains("\"orderNo\":" + orderNo, "\"tenantId\":" + TENANT,
                "\"payAmount\":19800", "\"eventId\":" + eventId);
        Map<String, Object> order = jdbc.queryForMap("SELECT created_at FROM orders WHERE order_no = ?", orderNo);
        assertThat(order.get("created_at")).isNotNull();
    }

    /**
     * 破坏性：半消息已发出，本地事务写库失败（模拟数据库故障）。
     * 结论：订单不存在、下单报原始错误，且消费者永远看不到这条消息 —— 不会出现"消息发了订单没建"。
     */
    @Test
    void localTxFailed_noOrder_andMessageNeverVisible() {
        doThrow(new DataAccessResourceFailureException("模拟数据库故障")).when(persistService).saveNewOrder(any());

        assertThatThrownBy(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        ArgumentCaptor<ReserveRequest> captor = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(client).reserve(captor.capture());
        long orderNo = captor.getValue().getOrderNo();

        assertThat(countOrders(orderNo)).isZero();
        // 半消息被 ROLLBACK：等足够久（> 测试 Broker 的回查周期）也收不到
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
                .until(() -> observer.received(orderNo).isEmpty());
    }
}
