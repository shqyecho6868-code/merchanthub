package com.merchanthub.product.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.inventory.model.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 预占：可售 → 预占。防超卖就靠这一句条件更新：
     * InnoDB 执行 UPDATE 时先对 sku_id 这一行加排他锁，再判断 WHERE 条件；
     * 并发请求在这一行上排队，每个请求看到的都是前一个提交后的最新 available，
     * available 不够时影响行数为 0，由调用方判定为“库存不足”。
     * tenant_id 条件做租户隔离：拿别的商家的 tenant_id 占不到这一行。
     *
     * @return 影响行数：1 = 预占成功；0 = 库存不足（或 SKU 不属于该租户 / 不存在）
     */
    @Update("""
            UPDATE inventory
            SET available = available - #{qty}, reserved = reserved + #{qty}
            WHERE sku_id = #{skuId} AND tenant_id = #{tenantId} AND deleted = 0
              AND available >= #{qty}
            """)
    int tryReserve(@Param("tenantId") long tenantId, @Param("skuId") long skuId, @Param("qty") int qty);
}
