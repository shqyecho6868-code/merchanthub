package com.merchanthub.product.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 预占请求：一个订单要占的所有 SKU，一次调用、一个本地事务，全占或全不占。
 * orderNo 是幂等键：同一个 orderNo 重复请求（Feign 重试等）只生效一次。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReserveRequest {

    @NotNull
    private Long orderNo;

    @NotNull
    private Long tenantId;

    @NotEmpty
    @Size(max = 50)
    @Valid
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull
        private Long skuId;
        @NotNull
        @Min(1)
        private Integer quantity;
    }
}
