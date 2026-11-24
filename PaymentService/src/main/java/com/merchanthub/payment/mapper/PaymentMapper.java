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
     * 回调幂等的核心：待支付 → 成功 的 CAS。并发的 N 次重复回调同时执行这条 SQL，InnoDB 行锁让它们排队，
     * 第一条把 status 改成 1 以后，后面的再判断 status = 0 不成立，影响行数为 0。
     */
    @Update("UPDATE payment SET status = 1, channel_trade_no = #{channelTradeNo}, paid_at = #{paidAt}, "
            + "success_attempt = #{attempt} WHERE pay_no = #{payNo} AND status = 0 AND deleted = 0")
    int markSuccess(@Param("payNo") long payNo, @Param("channelTradeNo") String channelTradeNo,
                    @Param("paidAt") LocalDateTime paidAt, @Param("attempt") String attempt);

    // ---------------- E3 对账 ----------------

    /** 支付成功、还没核对过、且成功已超过宽限期的支付单（走索引 (status, recon_status, paid_at)） */
    @Select("SELECT * FROM payment WHERE status = 1 AND recon_status = 0 AND paid_at < #{before} AND deleted = 0"
            + " ORDER BY paid_at LIMIT #{limit}")
    List<Payment> selectSuccessUnreconciled(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 核对一致：订单已处理过这张支付单的事件 */
    @Update("UPDATE payment SET recon_status = 1 WHERE pay_no = #{payNo} AND recon_status = 0")
    int markReconciled(@Param("payNo") long payNo);

    /** 补发了一次支付成功事件。带旧值做 CAS：两个实例同时补发同一张时只有一个计数成功 */
    @Update("UPDATE payment SET recon_retry = recon_retry + 1 WHERE pay_no = #{payNo} AND recon_status = 0"
            + " AND recon_retry = #{oldRetry}")
    int incrReconRetry(@Param("payNo") long payNo, @Param("oldRetry") int oldRetry);

    /** 补发多次仍未处理 / 订单不存在：不再自动处理，已告警转人工 */
    @Update("UPDATE payment SET recon_status = 3 WHERE pay_no = #{payNo} AND recon_status = 0")
    int markReconExhausted(@Param("payNo") long payNo);

    /** 创建已久仍是待支付的支付单（走索引 (status, created_at)） */
    @Select("SELECT * FROM payment WHERE status = 0 AND created_at < #{before} AND deleted = 0"
            + " ORDER BY created_at LIMIT #{limit}")
    List<Payment> selectStalePending(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 待支付 → 已关闭 的 CAS。和回调的"待支付 → 成功"抢同一行：谁先提交谁赢，输的一方影响 0 行。
     * 关闭赢了之后钱又到 → 回调看到"已关闭"，记异常待退款（PaymentService）
     */
    @Update("UPDATE payment SET status = 3 WHERE pay_no = #{payNo} AND status = 0 AND deleted = 0")
    int closeIfPending(@Param("payNo") long payNo);
}
