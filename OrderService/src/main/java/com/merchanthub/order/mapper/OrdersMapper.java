package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * Order status CAS: change to `to` only if the current status is still `from`. 1 row affected = transition succeeded; 0 rows = someone else already changed the status.
     * Includes tenant_id: the merchant in the event must match the order, preventing cross-tenant updates.
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
     * Current read of the order status (row lock, latest committed version). Must be used after a failed CAS to find out "what the status actually is now":
     * under InnoDB repeatable read, a plain SELECT reads the snapshot from the transaction's first read -- the order was already read before the CAS,
     * so a plain SELECT would still return the stale "pending payment" and misjudge "paid after close" as "duplicate payment" (caught by B3-下 PayTimeoutRaceIT, pitfalls P22)
     */
    /**
     * <b>Explicitly cross-tenant</b> lookup by order number (C1): only for the "payment succeeded" consumer -- it must detect "the event's merchant doesn't match the order's merchant" and record an anomaly;
     * querying with the tenant condition would make a mismatched order simply "not found", treated as "callback before order" and redelivered until the DLQ, burying the anomaly.
     * Bypassing the tenant plugin must be done in a dedicated method like this with a stated reason, never by casually disabling it on a general query.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} AND deleted = 0")
    Orders selectByOrderNoAcrossTenants(@Param("orderNo") long orderNo);

    /**
     * E3 timeout-order scan: still pending after the payment deadline + grace period (normally the ORDER_TIMEOUT scheduled message should have closed it long ago).
     * Cross-tenant: the scan runs as SYSTEM and doesn't add tenant_id anyway; uses index (status, expire_at)
     */
    @Select("SELECT order_no, tenant_id FROM orders WHERE status = 10 AND expire_at < #{before} AND deleted = 0"
            + " ORDER BY expire_at LIMIT #{limit}")
    List<Orders> selectExpiredPending(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Select("SELECT status FROM orders WHERE order_no = #{orderNo} AND deleted = 0 FOR UPDATE")
    Integer selectStatusForUpdate(@Param("orderNo") long orderNo);
}
