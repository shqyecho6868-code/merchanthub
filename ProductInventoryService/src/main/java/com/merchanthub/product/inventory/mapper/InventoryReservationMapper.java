package com.merchanthub.product.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InventoryReservationMapper extends BaseMapper<InventoryReservation> {

    default List<InventoryReservation> selectByOrderNo(long orderNo) {
        return selectList(Wrappers.<InventoryReservation>lambdaQuery().eq(InventoryReservation::getOrderNo, orderNo));
    }

    /**
     * Reservation record CAS: can change to `to` only if it's currently `from` (RESERVED). Duplicate releases / confirms, or a release racing a confirm,
     * all get exactly one "1 row affected" here, and only that one touches stock — this record itself is the inventory side's idempotency gate.
     */
    @Update("UPDATE inventory_reservation SET status = #{to} WHERE id = #{id} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    /**
     * Dangling-reservation candidates: orders whose reservations are still RESERVED after olderThanSeconds (uses idx_status_created).
     * Time is computed with the database's NOW(3): created_at is written by a database default, so comparing on the same clock is unaffected by app machine clock skew
     */
    @Select("SELECT DISTINCT order_no AS orderNo, tenant_id AS tenantId FROM inventory_reservation "
            + "WHERE status = 10 AND deleted = 0 AND created_at < NOW(3) - INTERVAL #{olderThanSeconds} SECOND "
            + "ORDER BY order_no LIMIT #{limit}")
    List<InventoryReservation> selectStaleReserved(@Param("olderThanSeconds") long olderThanSeconds, @Param("limit") int limit);
}
