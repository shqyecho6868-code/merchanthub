package com.merchanthub.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.payment.model.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Select("SELECT * FROM payment WHERE pay_no = #{payNo} AND deleted = 0")
    Payment selectByPayNo(@Param("payNo") long payNo);

    /**
     * The core of callback idempotency: the pending → succeeded CAS. When N concurrent duplicate callbacks run this SQL at once, InnoDB row locks queue them up;
     * once the first one sets status to 1, the condition status = 0 no longer holds for the rest, and they affect 0 rows.
     */
    @Update("UPDATE payment SET status = 1, channel_trade_no = #{channelTradeNo}, paid_at = #{paidAt}, "
            + "success_attempt = #{attempt} WHERE pay_no = #{payNo} AND status = 0 AND deleted = 0")
    int markSuccess(@Param("payNo") long payNo, @Param("channelTradeNo") String channelTradeNo,
                    @Param("paidAt") LocalDateTime paidAt, @Param("attempt") String attempt);

    // ---------------- E3 reconciliation ----------------

    /** Succeeded payments not yet reconciled whose success is older than the grace period (uses index (status, recon_status, paid_at)) */
    @Select("SELECT * FROM payment WHERE status = 1 AND recon_status = 0 AND paid_at < #{before} AND deleted = 0"
            + " ORDER BY paid_at LIMIT #{limit}")
    List<Payment> selectSuccessUnreconciled(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** Reconciled as consistent: order has already processed this payment's event */
    @Update("UPDATE payment SET recon_status = 1 WHERE pay_no = #{payNo} AND recon_status = 0")
    int markReconciled(@Param("payNo") long payNo);

    /** Re-sent the payment-success event once. CAS on the old value: if two instances re-send the same payment concurrently, only one increments the count */
    @Update("UPDATE payment SET recon_retry = recon_retry + 1 WHERE pay_no = #{payNo} AND recon_status = 0"
            + " AND recon_retry = #{oldRetry}")
    int incrReconRetry(@Param("payNo") long payNo, @Param("oldRetry") int oldRetry);

    /** Still unprocessed after several re-sends / order missing: stop auto-handling; an alert has been raised for manual handling */
    @Update("UPDATE payment SET recon_status = 3 WHERE pay_no = #{payNo} AND recon_status = 0")
    int markReconExhausted(@Param("payNo") long payNo);

    /** Payments created long ago and still pending (uses index (status, created_at)) */
    @Select("SELECT * FROM payment WHERE status = 0 AND created_at < #{before} AND deleted = 0"
            + " ORDER BY created_at LIMIT #{limit}")
    List<Payment> selectStalePending(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * The pending → closed CAS. It competes for the same row with the callback's "pending → succeeded": whoever commits first wins, the loser affects 0 rows.
     * If money arrives after the close won → the callback sees "closed" and records an anomaly for refund (PaymentService)
     */
    @Update("UPDATE payment SET status = 3 WHERE pay_no = #{payNo} AND status = 0 AND deleted = 0")
    int closeIfPending(@Param("payNo") long payNo);
}
