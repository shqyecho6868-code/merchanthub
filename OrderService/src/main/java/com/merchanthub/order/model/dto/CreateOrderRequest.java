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
 * <p>
 * tenantId（C2）：买家在哪个商家的店里下单。店铺页 / 确认页本来就知道是哪个店，所以前端一定能带上。
 * 它只用来<b>在入口按商家限流</b>（被限的请求不消耗任何下游资源）；真正落库的商家仍以 SKU 归属为准，
 * 两者不一致直接拒绝（SKU_TENANT_MISMATCH），不能靠改这个字段把订单记到别的商家名下。
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

    /** 下单的商家（C2，限流用的热点参数），必须和 SKU 归属一致 */
    @NotNull
    private Long tenantId;

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
