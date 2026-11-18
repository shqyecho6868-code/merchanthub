package com.merchanthub.order.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReserveRequest {
    private Long orderNo;
    private Long tenantId;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long skuId;
        private Integer quantity;
    }
}
