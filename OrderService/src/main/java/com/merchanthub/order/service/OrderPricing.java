package com.merchanthub.order.service;

import com.merchanthub.order.client.dto.SkuSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pricing: uses only unit prices fetched server-side. All amounts are long, in cents.
 * There are no discounts yet, so discount is always 0; but the fields and the "allocate to line items" structure are in place so adding discounts only touches this class.
 */
public final class OrderPricing {

    private OrderPricing() {
    }

    public record Line(SkuSnapshot sku, int quantity, long totalAmount, long discountAmount, long payAmount) {
    }

    public record Result(List<Line> lines, long totalAmount, long discountAmount, long payAmount) {
    }

    /** @param quantities skuId -> quantity (duplicate SKUs already merged) */
    public static Result price(Map<Long, Integer> quantities, Map<Long, SkuSnapshot> skus) {
        List<Line> lines = new ArrayList<>();
        long total = 0;
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            SkuSnapshot sku = skus.get(e.getKey());
            // Math.multiplyExact: throws on overflow instead of silently producing a negative amount
            long lineTotal = Math.multiplyExact(sku.getPrice(), (long) e.getValue());
            long lineDiscount = 0;
            lines.add(new Line(sku, e.getValue(), lineTotal, lineDiscount, lineTotal - lineDiscount));
            total = Math.addExact(total, lineTotal);
        }
        long discount = lines.stream().mapToLong(Line::discountAmount).sum();
        return new Result(lines, total, discount, total - discount);
    }
}
