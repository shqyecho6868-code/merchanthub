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
 * Reservation request: all SKUs an order needs, in one call and one local transaction — all reserved or none.
 * orderNo is the idempotency key: repeated requests with the same orderNo (Feign retries, etc.) take effect only once.
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
