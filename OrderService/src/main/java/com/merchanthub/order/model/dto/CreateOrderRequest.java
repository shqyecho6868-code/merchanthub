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
 * Order placement request. Only accepts "what and how many"; price, amounts, time, and merchant are all decided server-side.
 * buyerId isn't here: it comes from the gateway-injected X-User-Id header, preventing impersonation.
 * <p>
 * tenantId: which merchant's store the buyer is ordering from. The store / checkout page already knows the store, so the frontend can always send it.
 * It's used only for <b>per-merchant rate limiting at the entry</b> (throttled requests consume no downstream resources); the persisted merchant is still determined by SKU ownership,
 * and a mismatch is rejected outright (SKU_TENANT_MISMATCH), so changing this field can't book an order under another merchant.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotEmpty
    @Size(max = 20)
    @Valid
    private List<Item> items;

    /** Amount payable (cents) shown to the buyer on the checkout page, optional; only used for comparison, not for pricing */
    private Long expectedPayAmount;

    /** Merchant being ordered from (the hot parameter for rate limiting); must match SKU ownership */
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
