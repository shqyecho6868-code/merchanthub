package com.merchanthub.order;

import com.merchanthub.order.controller.InternalOrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Batch existence query used by the dangling-reservation scan: returns only order numbers that really exist */
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
class InternalOrderControllerIT extends OrderItSupport {

    @Autowired
    private InternalOrderController controller;

    @Test
    void statuses_returnsOnlyCreatedOrders() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        assertThat(controller.statuses(List.of(orderNo, 42L)).getData()).containsOnlyKeys(orderNo);
        assertThat(controller.statuses(List.of()).getData()).isEmpty();
    }

    /** E3: at most 500 per call, so callers can't slow order down by sending too many at once */
    @Test
    void batchTooLarge_rejected() {
        List<Long> many = java.util.stream.LongStream.range(0, 501).boxed().toList();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.statuses(many))
                .isInstanceOf(com.merchanthub.common.exception.BusinessException.class);
    }
}
