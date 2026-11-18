package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SkuQueryRequest {
    @NotEmpty
    @Size(max = 50)
    private List<Long> skuIds;
}
