package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 订单状态 CAS：只有当前状态仍是 from 时才改成 to。影响 1 行 = 本次流转成功；0 行 = 状态已被别人改过。
     * 带 tenant_id：事件里的商家必须和订单一致，防止串租户改单。
     */
    @Update("UPDATE orders SET status = #{to}, paid_at = CASE WHEN #{to} = 20 THEN #{now} ELSE paid_at END "
            + "WHERE order_no = #{orderNo} AND tenant_id = #{tenantId} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("orderNo") long orderNo, @Param("tenantId") long tenantId,
                  @Param("from") int from, @Param("to") int to, @Param("now") LocalDateTime now);
}
