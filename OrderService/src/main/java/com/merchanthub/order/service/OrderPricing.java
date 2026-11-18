package com.merchanthub.order.service;

import com.merchanthub.order.client.dto.SkuSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计价：只用服务端查到的单价。金额全部 long、单位分。
 * B1 没有优惠，discount 全为 0；但字段和“分摊到明细行”的结构先留好，以后加优惠只改这里。
 */
public final class OrderPricing {

    private OrderPricing() {
    }

    public record Line(SkuSnapshot sku, int quantity, long totalAmount, long discountAmount, long payAmount) {
    }

    public record Result(List<Line> lines, long totalAmount, long discountAmount, long payAmount) {
    }

    /** @param quantities skuId → 数量（已合并重复 SKU） */
    public static Result price(Map<Long, Integer> quantities, Map<Long, SkuSnapshot> skus) {
        List<Line> lines = new ArrayList<>();
        long total = 0;
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            SkuSnapshot sku = skus.get(e.getKey());
            // Math.multiplyExact：溢出时抛异常，而不是悄悄变成负数金额
            long lineTotal = Math.multiplyExact(sku.getPrice(), (long) e.getValue());
            long lineDiscount = 0;
            lines.add(new Line(sku, e.getValue(), lineTotal, lineDiscount, lineTotal - lineDiscount));
            total = Math.addExact(total, lineTotal);
        }
        long discount = lines.stream().mapToLong(Line::discountAmount).sum();
        return new Result(lines, total, discount, total - discount);
    }
}
