package com.merchanthub.order.job;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox archiving: moves records sent longer ago than the retention period into order_outbox_archive.
 * <p>
 * Why archive: each order has at least 2-3 outbox rows (created / timeout scheduled / paid or closed); without cleanup the main table grows forever,
 * and although the scan SQL uses an index, a bigger table makes writes, backups, and DDL slower. Archive instead of delete: we can still check afterwards "was this event sent, and when".
 * Only status = 1 is moved: pending (0) and failed (2) rows must stay in the main table, or they'd "disappear".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxArchiver {

    private final OrderOutboxMapper outboxMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Value("${merchanthub.order.jobs.archive-retention:7d}")
    private Duration retention;
    @Value("${merchanthub.order.jobs.batch-size:200}")
    private int batchSize;

    /** @return number of records archived this time (in batches, one small transaction per batch, to avoid one big transaction locking the table for long) */
    public int archive() {
        return TenantContext.callAsSystem(() -> {
            LocalDateTime before = LocalDateTime.now(clock).minus(retention);
            int total = 0;
            for (int round = 0; round < 50; round++) {   // at most 50 batches per run; the rest is moved next time
                List<Long> ids = outboxMapper.selectArchivable(before, batchSize);
                if (ids.isEmpty()) {
                    break;
                }
                Integer moved = tx.execute(s -> {
                    outboxMapper.copyToArchive(ids);
                    return outboxMapper.deleteSent(ids);
                });
                total += moved == null ? 0 : moved;
            }
            if (total > 0) {
                log.info("Outbox archiving done, {} records (sent_at < {})", total, before);
            }
            return total;
        });
    }
}
