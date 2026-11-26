package com.merchanthub.product.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.inventory.model.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * Reserve: available → reserved. Overselling is prevented by this single conditional update:
     * when InnoDB executes the UPDATE it first takes an exclusive lock on the sku_id row, then evaluates the WHERE condition;
     * concurrent requests queue on this row, and each sees the latest available committed by the previous one;
     * when available is insufficient the affected row count is 0, which the caller treats as "insufficient stock".
     * The tenant_id condition enforces tenant isolation: another merchant's tenant_id can't grab this row.
     *
     * @return affected rows: 1 = reserved; 0 = insufficient stock (or the SKU doesn't belong to this tenant / doesn't exist)
     */
    @Update("""
            UPDATE inventory
            SET available = available - #{qty}, reserved = reserved + #{qty}
            WHERE sku_id = #{skuId} AND tenant_id = #{tenantId} AND deleted = 0
              AND available >= #{qty}
            """)
    int tryReserve(@Param("tenantId") long tenantId, @Param("skuId") long skuId, @Param("qty") int qty);

    /**
     * Confirm deduction (payment succeeded): reserved → actually sold. available stays the same (already reduced at order time), reserved is decremented.
     * reserved >= qty is a defensive condition: it always holds normally; if not, the data is already inconsistent and the caller throws to roll back and alert.
     */
    @Update("""
            UPDATE inventory SET reserved = reserved - #{qty}
            WHERE sku_id = #{skuId} AND tenant_id = #{tenantId} AND deleted = 0 AND reserved >= #{qty}
            """)
    int confirm(@Param("tenantId") long tenantId, @Param("skuId") long skuId, @Param("qty") int qty);

    /** Release (order closed / dangling reservation): return the reserved quantity to available. Same condition as confirm */
    @Update("""
            UPDATE inventory SET available = available + #{qty}, reserved = reserved - #{qty}
            WHERE sku_id = #{skuId} AND tenant_id = #{tenantId} AND deleted = 0 AND reserved >= #{qty}
            """)
    int release(@Param("tenantId") long tenantId, @Param("skuId") long skuId, @Param("qty") int qty);
}
