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

    /** Due pending records, using index (status, next_retry_at) */
    @Select("SELECT * FROM order_outbox WHERE status = 0 AND deleted = 0 AND next_retry_at <= #{now}"
            + " ORDER BY next_retry_at LIMIT #{limit}")
    List<OrderOutbox> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Claim a record: only push next_retry_at to the lease deadline, <b>without changing status</b>.
     * Only one instance (or thread) can update it; only the one that gets 1 sends it. During the lease other instances' scan condition next_retry_at <= now fails, so they don't see it.
     * <p>
     * Why not set it to 1 (sent) here: on send failure markRetry's condition is status = 0, which would match no rows,
     * and the record would be stuck as "sent" forever, never scanned again -- the message lost with no error at all (the bug found in the B2 🔍 code review).
     * If the lease expires without a result (e.g. the process was killed mid-send), the record automatically becomes "due" again and the next scan picks it up.
     */
    @Update("UPDATE order_outbox SET next_retry_at = #{leaseUntil}"
            + " WHERE id = #{id} AND status = 0 AND next_retry_at <= #{now}")
    int claim(@Param("id") long id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Set to 1 only after a successful send; the status = 0 condition keeps the transition one-directional */
    @Update("UPDATE order_outbox SET status = 1, sent_at = #{now} WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

    /**
     * Send failure: retry count +1 and push back the next send time; when the limit is reached pass status 2 (failed, no more automatic retries).
     * CAS on the old retry_count so two threads recording a failure at once don't miscount.
     */
    @Update("UPDATE order_outbox SET status = #{status}, retry_count = #{newRetryCount},"
            + " next_retry_at = #{nextRetryAt}, last_error = #{error}"
            + " WHERE id = #{id} AND status = 0 AND retry_count = #{oldRetryCount}")
    int markRetry(@Param("id") long id, @Param("oldRetryCount") int oldRetryCount,
                  @Param("newRetryCount") int newRetryCount, @Param("status") int status,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("error") String error);

    /** E3 archiving: ids of a batch of records "sent and older than before" (using index (status, sent_at)) */
    @Select("SELECT id FROM order_outbox WHERE status = 1 AND sent_at < #{before} ORDER BY id LIMIT #{limit}")
    List<Long> selectArchivable(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** Copy to the archive table first (INSERT IGNORE: safe to rerun if the previous round copied but didn't delete), then delete from the main table, both in one transaction */
    @InterceptorIgnore(tenantLine = "true")   // Archiving is a cross-merchant system task; INSERT ... SELECT needs no tenant-plugin rewrite
    @Insert("<script>INSERT IGNORE INTO order_outbox_archive SELECT * FROM order_outbox WHERE status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int copyToArchive(@Param("ids") List<Long> ids);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("<script>DELETE FROM order_outbox WHERE status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteSent(@Param("ids") List<Long> ids);

    /** E3 alerting: records whose delivery failures hit the limit (status = 2) */
    @Select("SELECT * FROM order_outbox WHERE status = 2 AND deleted = 0 ORDER BY id LIMIT #{limit}")
    List<OrderOutbox> selectFailed(@Param("limit") int limit);
}
