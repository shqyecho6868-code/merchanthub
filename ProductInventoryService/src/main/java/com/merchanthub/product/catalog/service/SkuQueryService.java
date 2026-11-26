package com.merchanthub.product.catalog.service;

import com.merchanthub.product.catalog.mapper.SkuMapper;
import com.merchanthub.product.catalog.model.vo.SkuSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Only "looks up", never decides whether something can be sold: ordering rules like on sale, listed, same merchant are decided by order,
 * so the product service doesn't need to know ordering business rules (API boundary: product provides data, order makes decisions).
 * SKUs that aren't found are simply absent from the result; the caller compares counts.
 */
@Service
@RequiredArgsConstructor
public class SkuQueryService {

    private final SkuMapper skuMapper;

    public List<SkuSnapshotVO> querySnapshots(List<Long> skuIds) {
        return skuMapper.selectSnapshots(skuIds);
    }
}
