package com.merchanthub.order.search;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canal -> ES order sync (demo only). Canal poses as a MySQL replica reading the binlog; here we pull row changes of mh_order.orders / order_item,
 * extract the order numbers, and hand them to {@link OrderSearchIndexer} to look up and overwrite.
 * <p>
 * Retry on failure: if any order in a batch fails -> rollback, Canal hands us the <b>whole batch</b> again next time (position not advanced), retried a few seconds later;
 * since sync is "look up + full overwrite", reprocessing a batch has no side effects. If it keeps failing (e.g. ES down) the position stays put,
 * and after recovery it resumes from the checkpoint without losing changes -- the cost is ES growing staler (lag) meanwhile.
 * <p>
 * Off by default (tests and environments without Canal don't connect); enable for local demos with deploy/e2/start-order-canal.sh.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "merchanthub.order.canal.enabled", havingValue = "true")
public class CanalOrderSync {

    static final Set<String> TABLES = Set.of("orders", "order_item");

    private final OrderSearchIndexer indexer;

    @Value("${merchanthub.order.canal.host:127.0.0.1}")
    private String host;
    @Value("${merchanthub.order.canal.port:11111}")
    private int port;
    @Value("${merchanthub.order.canal.destination:mh_order}")
    private String destination;
    @Value("${merchanthub.order.canal.batch-size:200}")
    private int batchSize;

    private volatile boolean running;
    private Thread worker;

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::loop, "canal-order-sync");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            CanalConnector connector = CanalConnectors.newSingleConnector(new InetSocketAddress(host, port), destination, "", "");
            try {
                connector.connect();
                connector.subscribe("mh_order\\.(orders|order_item)");
                connector.rollback();   // start from the last acked position
                log.info("Canal connected {}:{} destination={}", host, port, destination);
                while (running) {
                    Message batch = connector.getWithoutAck(batchSize);
                    long batchId = batch.getId();
                    if (batchId == -1 || batch.getEntries().isEmpty()) {
                        Thread.sleep(500);
                        continue;
                    }
                    try {
                        for (long orderNo : orderNosOf(batch.getEntries())) {
                            indexer.sync(orderNo);
                        }
                        connector.ack(batchId);
                    } catch (Exception e) {
                        // Don't ack; roll back to the start of this batch so the whole batch is redone next time (sync is idempotent)
                        log.warn("ES sync failed, rolling back batch {} to retry later", batchId, e);
                        connector.rollback(batchId);
                        Thread.sleep(3000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Canal connection error, reconnecting in 5s {}:{}", host, port, e);
                sleepQuietly(5000);
            } finally {
                try {
                    connector.disconnect();
                } catch (RuntimeException ignored) {
                    // A failed disconnect doesn't affect reconnecting
                }
            }
        }
    }

    /**
     * Extract the affected order numbers from a binlog batch (deduplicated, order preserved). Inserts/updates use after-change columns, deletes use before-change columns.
     * Both the order header and item tables have an order_no column, so they're treated the same.
     */
    static List<Long> orderNosOf(List<CanalEntry.Entry> entries) throws InvalidProtocolBufferException {
        Set<Long> orderNos = new LinkedHashSet<>();
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA
                    || !TABLES.contains(entry.getHeader().getTableName())) {
                continue;
            }
            CanalEntry.RowChange change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            for (CanalEntry.RowData row : change.getRowDatasList()) {
                List<CanalEntry.Column> cols = change.getEventType() == CanalEntry.EventType.DELETE
                        ? row.getBeforeColumnsList() : row.getAfterColumnsList();
                for (CanalEntry.Column c : cols) {
                    if ("order_no".equals(c.getName()) && !c.getIsNull()) {
                        orderNos.add(Long.parseLong(c.getValue()));
                    }
                }
            }
        }
        return List.copyOf(orderNos);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
