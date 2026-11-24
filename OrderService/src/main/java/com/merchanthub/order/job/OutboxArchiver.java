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
 * 本地消息表归档（E3）：已发送超过保留期的记录搬到 order_outbox_archive。
 * <p>
 * 为什么要归档：outbox 每单至少 2~3 行（创建 / 超时定时 / 支付或关单），不清理的话主表无限增长，
 * 扫描 SQL 虽然走索引，但表越大写入、备份、DDL 越慢。归档而不是直接删：事后排查"这条事件当时发没发、何时发的"还查得到。
 * 只搬 status = 1：待发送（0）和失败（2）的必须留在主表，否则就"消失"了。
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

    /** @return 本次归档的记录数（分批，每批一个小事务，避免一个大事务长时间锁表） */
    public int archive() {
        return TenantContext.callAsSystem(() -> {
            LocalDateTime before = LocalDateTime.now(clock).minus(retention);
            int total = 0;
            for (int round = 0; round < 50; round++) {   // 单次执行最多 50 批，剩下的下次再搬
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
                log.info("本地消息表归档完成 {} 条（sent_at < {}）", total, before);
            }
            return total;
        });
    }
}
