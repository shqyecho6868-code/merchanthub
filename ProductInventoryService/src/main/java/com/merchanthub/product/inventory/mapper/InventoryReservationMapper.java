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
     * 预占记录 CAS：只有当前还是 from（RESERVED）才能改成 to。重复的释放 / 确认、释放与确认并发，
     * 都在这里只有一个能拿到"影响 1 行"，拿到的才去动库存 —— 这条记录本身就是库存侧的幂等闸门。
     */
    @Update("UPDATE inventory_reservation SET status = #{to} WHERE id = #{id} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    /**
     * 悬空预占候选：预占了 olderThanSeconds 秒以上仍是 RESERVED 的订单（走 idx_status_created）。
     * 用数据库的 NOW(3) 算时间：created_at 由数据库默认值写入，用同一个时钟比较不受应用机器时钟偏差影响
     */
    @Select("SELECT DISTINCT order_no AS orderNo, tenant_id AS tenantId FROM inventory_reservation "
            + "WHERE status = 10 AND deleted = 0 AND created_at < NOW(3) - INTERVAL #{olderThanSeconds} SECOND "
            + "ORDER BY order_no LIMIT #{limit}")
    List<InventoryReservation> selectStaleReserved(@Param("olderThanSeconds") long olderThanSeconds, @Param("limit") int limit);
}
