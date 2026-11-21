package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 订单状态 CAS：只有当前状态仍是 from 时才改成 to。影响 1 行 = 本次流转成功；0 行 = 状态已被别人改过。
     * 带 tenant_id：事件里的商家必须和订单一致，防止串租户改单。
     */
    @Update("UPDATE orders SET status = #{to}, "
            + "paid_at = CASE WHEN #{to} = 20 THEN #{now} ELSE paid_at END, "
            + "closed_at = CASE WHEN #{to} = 50 THEN #{now} ELSE closed_at END, "
            + "close_reason = CASE WHEN #{to} = 50 THEN #{event} ELSE close_reason END "
            + "WHERE order_no = #{orderNo} AND tenant_id = #{tenantId} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("orderNo") long orderNo, @Param("tenantId") long tenantId,
                  @Param("from") int from, @Param("to") int to, @Param("now") LocalDateTime now,
                  @Param("event") String event);

    /**
     * 当前读订单状态（加行锁，读最新已提交版本）。CAS 失败后判断"现在到底是什么状态"必须用它：
     * InnoDB 可重复读下，普通 SELECT 读的是本事务第一次读时的快照 —— CAS 前已经读过订单，
     * 再普通 SELECT 拿到的还是旧的"待支付"，会把"关单后支付"误判成"重复支付"（B3-下 PayTimeoutRaceIT 抓到，pitfalls P22）
     */
    @Select("SELECT status FROM orders WHERE order_no = #{orderNo} AND deleted = 0 FOR UPDATE")
    Integer selectStatusForUpdate(@Param("orderNo") long orderNo);
}
