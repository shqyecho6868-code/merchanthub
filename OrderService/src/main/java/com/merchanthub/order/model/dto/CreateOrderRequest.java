package com.merchanthub.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下单请求。只收“买什么、买几个”；价格、金额、时间、商家全部由服务端决定（B1 笔记 5.1）。
 * buyerId 不在这里：从网关注入的 X-User-Id 请求头取，防止冒充。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotEmpty
    @Size(max = 20)
    @Valid
    private List<Item> items;

    /** 买家确认页看到的应付金额（分），可选；只用于比对，不参与计价 */
    private Long expectedPayAmount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull
        private Long skuId;
        @NotNull
        @Min(1)
        @Max(999)
        private Integer quantity;
    }
}
