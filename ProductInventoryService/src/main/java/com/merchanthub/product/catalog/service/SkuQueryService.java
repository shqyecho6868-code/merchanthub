package com.merchanthub.product.catalog.service;

import com.merchanthub.product.catalog.mapper.SkuMapper;
import com.merchanthub.product.catalog.model.vo.SkuSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 只负责“查”，不判断能不能卖：在售、上架、同商家这些下单规则由 order 判断，
 * 这样商品服务不需要了解下单的业务规则（接口边界：product 提供数据，order 做决策）。
 * 查不到的 SKU 不会出现在结果里，由调用方比对数量。
 */
@Service
@RequiredArgsConstructor
public class SkuQueryService {

    private final SkuMapper skuMapper;

    public List<SkuSnapshotVO> querySnapshots(List<Long> skuIds) {
        return skuMapper.selectSnapshots(skuIds);
    }
}
