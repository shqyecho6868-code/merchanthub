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
 * Option 1 (transactional message) happy path: local transaction succeeds -> message visible; local transaction fails -> message never visible.
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
     * Destructive: the half message is sent, but the local transaction's DB write fails (simulated DB failure).
     * Conclusion: the order doesn't exist, placement reports the original error, and consumers never see the message -- no "message sent, order never created".
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
        // The half message was ROLLed BACK: not received even after waiting long enough (> the test broker's check-back interval)
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
                .until(() -> observer.received(orderNo).isEmpty());
    }
}
