package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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
    /**
     * <b>显式跨租户</b>按订单号查（C1）：只给"支付成功"消费用 —— 它要能发现"事件里的商家和订单的商家对不上"并记异常，
     * 带着租户条件查的话对不上的订单直接"查不到"，会被当成"回调早于订单"一直重投到死信，异常就被埋没了。
     * 绕过租户插件必须像这样单独写一个方法、写清理由，不能在通用查询上随手关掉。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} AND deleted = 0")
    Orders selectByOrderNoAcrossTenants(@Param("orderNo") long orderNo);

    @Select("SELECT status FROM orders WHERE order_no = #{orderNo} AND deleted = 0 FOR UPDATE")
    Integer selectStatusForUpdate(@Param("orderNo") long orderNo);
}
