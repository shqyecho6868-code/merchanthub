package com.merchanthub.order;

import com.merchanthub.common.trace.TraceContext;
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

/** 方案二（本地消息表）：订单和待发消息同生共死；提交后快路径立即投递 */
// 只测快路径（补偿扫描在测试配置里默认关闭，见 src/test/resources/application.properties）
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
class OrderOutboxIT extends OrderItSupport {

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
    void orderAndOutboxRowCommittedTogether_thenDelivered() {
        // 模拟 TraceIdFilter 已经把网关传来的 traceId 放进了下单请求线程的 MDC
        String traceId = "it-outbox-trace-0001";
        long orderNo;
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CREATED'", String.valueOf(orderNo));
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("topic")).isEqualTo(OrderEventTopics.TOPIC);
        assertThat(row.get("trace_id")).as("traceId 随 outbox 行落库（发送在另一个线程，MDC 跟不过去）").isEqualTo(traceId);

        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
        MessageExt m = observer.received(orderNo).get(0);
        assertThat(m.getUserProperty(OrderEventTopics.PROP_EVENT_ID)).isEqualTo(String.valueOf(row.get("event_id")));
        // 链路追踪：下单请求的 traceId 经 outbox 行 → 线程池投递 → 消息属性，消费者（consumeAs）据此恢复到 MDC
        assertThat(m.getUserProperty(TraceContext.MQ_PROPERTY)).isEqualTo(traceId);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT status FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CREATED'", Integer.class, String.valueOf(orderNo)) == 1);
    }

    /** 写订单失败 → 整个本地事务回滚：订单和消息记录都不存在，也就不可能发出消息 */
    @Test
    void orderWriteFails_noOrder_noOutboxRow() {
        doThrow(new DataAccessResourceFailureException("模拟数据库故障")).when(persistService).saveNewOrder(any());

        assertThatThrownBy(() -> orderCreateService.createOrder(BUYER, req(null, 101, 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        ArgumentCaptor<ReserveRequest> captor = ArgumentCaptor.forClass(ReserveRequest.class);
        verify(client).reserve(captor.capture());
        long orderNo = captor.getValue().getOrderNo();
        assertThat(countOrders(orderNo)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox", Long.class)).isZero();
    }
}
