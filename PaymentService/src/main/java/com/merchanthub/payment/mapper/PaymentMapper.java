package com.merchanthub.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.payment.model.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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
}
