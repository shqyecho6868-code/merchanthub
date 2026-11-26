package com.merchanthub.user.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.user.approval.model.entity.ApprovalOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ApprovalOutboxMapper extends BaseMapper<ApprovalOutbox> {

    /** Records due for sending, using the (status, next_retry_at) index */
    @Select("SELECT * FROM approval_outbox WHERE status = 0 AND deleted = 0 AND next_retry_at <= #{now}"
            + " ORDER BY next_retry_at LIMIT #{limit}")
    List<ApprovalOutbox> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Claim a record: only push next_retry_at out to the lease deadline, <b>without changing status</b>.
     * Only one instance (or thread) can update it; whoever gets 1 sends it. During the lease, other instances' scan condition next_retry_at <= now doesn't match, so they can't see it.
     * <p>
     * Why not change status: see order's OrderOutboxMapper.claim (a bug caught in code review); here too we only extend the lease.
     * If the lease expires without a result (e.g. the process was killed mid-send), the record automatically becomes "due" again and the next scan picks it up.
     */
    @Update("UPDATE approval_outbox SET next_retry_at = #{leaseUntil}"
            + " WHERE id = #{id} AND status = 0 AND next_retry_at <= #{now}")
    int claim(@Param("id") long id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Set to 1 only on successful send; conditioned on status = 0 so the status only moves one way */
    @Update("UPDATE approval_outbox SET status = 1, sent_at = #{now} WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

    /**
     * Send failed: retry count +1 and push back the next send time; when the count hits the limit, status is passed as 2 (failed, no more automatic retries).
     * CAS on the old retry_count, so two threads recording a failure at the same time can't miscount.
     */
    @Update("UPDATE approval_outbox SET status = #{status}, retry_count = #{newRetryCount},"
            + " next_retry_at = #{nextRetryAt}, last_error = #{error}"
            + " WHERE id = #{id} AND status = 0 AND retry_count = #{oldRetryCount}")
    int markRetry(@Param("id") long id, @Param("oldRetryCount") int oldRetryCount,
                  @Param("newRetryCount") int newRetryCount, @Param("status") int status,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("error") String error);
}
