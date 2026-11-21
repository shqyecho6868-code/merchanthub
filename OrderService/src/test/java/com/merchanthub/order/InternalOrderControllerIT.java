package com.merchanthub.order;

import com.merchanthub.order.controller.InternalOrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 悬空预占扫描依赖的批量存在性查询：只返回真实存在的订单号 */
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
class InternalOrderControllerIT extends OrderItSupport {

    @Autowired
    private InternalOrderController controller;

    @Test
    void existing_returnsOnlyCreatedOrders() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        assertThat(controller.existing(List.of(orderNo, 42L)).getData()).containsExactly(orderNo);
        assertThat(controller.existing(List.of()).getData()).isEmpty();
    }
}
