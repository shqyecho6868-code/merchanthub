package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderOutboxMapper extends BaseMapper<OrderOutbox> {

    /** 到期待发送的记录，走索引 (status, next_retry_at) */
    @Select("SELECT * FROM order_outbox WHERE status = 0 AND deleted = 0 AND next_retry_at <= #{now}"
            + " ORDER BY next_retry_at LIMIT #{limit}")
    List<OrderOutbox> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 抢占一条记录：只把 next_retry_at 推到租约截止时间，<b>不改 status</b>。
     * 只有一个实例（或线程）能更新成功，返回 1 的才去发送；租约内其他实例的扫描条件 next_retry_at <= now 不满足，查不到它。
     * <p>
     * 为什么不能在这里改成 1（已发送）：发送失败时 markRetry 的条件是 status = 0，会一行都匹配不上，
     * 这条记录就永远停在"已发送"，扫描再也查不到 —— 消息丢失且无任何报错（B2 🔍 审代码找到的 bug）。
     * 租约到期还没有结果（比如进程在发送途中被 kill），记录自动重新变成"到期"，被下一轮扫描接手。
     */
    @Update("UPDATE order_outbox SET next_retry_at = #{leaseUntil}"
            + " WHERE id = #{id} AND status = 0 AND next_retry_at <= #{now}")
    int claim(@Param("id") long id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 只有发送成功才改成 1；带 status = 0 条件，状态只能单向流转 */
    @Update("UPDATE order_outbox SET status = 1, sent_at = #{now} WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

    /**
     * 发送失败：重试次数 +1、推迟下次发送时间；次数到上限时 status 传 2（失败，不再自动重试）。
     * 带上旧的 retry_count 做 CAS，防止两个线程同时记失败把次数算错。
     */
    @Update("UPDATE order_outbox SET status = #{status}, retry_count = #{newRetryCount},"
            + " next_retry_at = #{nextRetryAt}, last_error = #{error}"
            + " WHERE id = #{id} AND status = 0 AND retry_count = #{oldRetryCount}")
    int markRetry(@Param("id") long id, @Param("oldRetryCount") int oldRetryCount,
                  @Param("newRetryCount") int newRetryCount, @Param("status") int status,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("error") String error);
}
