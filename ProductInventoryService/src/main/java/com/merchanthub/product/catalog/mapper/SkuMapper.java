package com.merchanthub.product.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.catalog.model.entity.Sku;
import com.merchanthub.product.catalog.model.vo.SkuSnapshotVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /** Fetch the SKU together with its product in one query, for the order snapshot. Logically deleted SKUs / products are treated as nonexistent */
    @Select("""
            <script>
            SELECT s.id AS sku_id, s.tenant_id, s.product_id, p.name AS product_name,
                   p.description AS product_description, p.main_image, s.sku_code, s.spec_json, s.price,
                   s.status AS sku_status, p.status AS product_status
            FROM sku s JOIN product p ON p.id = s.product_id AND p.deleted = 0
            WHERE s.deleted = 0 AND s.id IN
            <foreach collection="skuIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SkuSnapshotVO> selectSnapshots(@Param("skuIds") Collection<Long> skuIds);
}
