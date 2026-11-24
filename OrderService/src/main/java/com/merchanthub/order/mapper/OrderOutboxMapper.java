package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
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

    /** E3 归档：一批"已发送且早于 before"的记录 id（走索引 (status, sent_at)） */
    @Select("SELECT id FROM order_outbox WHERE status = 1 AND sent_at < #{before} ORDER BY id LIMIT #{limit}")
    List<Long> selectArchivable(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 先复制到归档表（INSERT IGNORE：上一轮复制了但没删掉时重跑不报错），再删主表，两步在同一个事务里 */
    @InterceptorIgnore(tenantLine = "true")   // 归档是跨商家的系统任务；INSERT ... SELECT 不需要租户插件改写
    @Insert("<script>INSERT IGNORE INTO order_outbox_archive SELECT * FROM order_outbox WHERE status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int copyToArchive(@Param("ids") List<Long> ids);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("<script>DELETE FROM order_outbox WHERE status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteSent(@Param("ids") List<Long> ids);

    /** E3 告警：投递失败达到上限（status = 2）的记录 */
    @Select("SELECT * FROM order_outbox WHERE status = 2 AND deleted = 0 ORDER BY id LIMIT #{limit}")
    List<OrderOutbox> selectFailed(@Param("limit") int limit);
}
