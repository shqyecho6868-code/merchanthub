package com.merchanthub.order.service;

import com.merchanthub.order.client.dto.SkuSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPricingTest {

    private static SkuSnapshot sku(long id, long price) {
        SkuSnapshot s = new SkuSnapshot();
        s.setSkuId(id);
        s.setPrice(price);
        return s;
    }

    @Test
    void sumsLinesInCents() {
        Map<Long, Integer> qty = new TreeMap<>(Map.of(101L, 2, 102L, 1));
        OrderPricing.Result r = OrderPricing.price(qty, Map.of(101L, sku(101, 9900), 102L, sku(102, 1999)));

        assertThat(r.totalAmount()).isEqualTo(2 * 9900 + 1999);
        assertThat(r.discountAmount()).isZero();
        assertThat(r.payAmount()).isEqualTo(r.totalAmount());
        assertThat(r.lines()).extracting(OrderPricing.Line::totalAmount).containsExactly(19800L, 1999L);
    }

    @Test
    void overflowThrowsInsteadOfNegativeAmount() {
        assertThatThrownBy(() -> OrderPricing.price(Map.of(1L, 2), Map.of(1L, sku(1, Long.MAX_VALUE))))
                .isInstanceOf(ArithmeticException.class);
    }
}
