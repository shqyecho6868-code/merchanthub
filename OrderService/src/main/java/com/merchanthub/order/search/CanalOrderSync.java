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
 * Canal → ES 订单同步（E2，只演示）。Canal 伪装成 MySQL 从库读 binlog，这里拉取 mh_order.orders / order_item 的行变更，
 * 取出订单号交给 {@link OrderSearchIndexer} 回查并覆盖。
 * <p>
 * 失败重试：一批处理中任何一单失败 → rollback，Canal 下次把<b>整批</b>重新给我们（位点没前进），隔几秒重试；
 * 因为同步是"回查 + 整篇覆盖"，同一批重复处理没有副作用。一直失败（如 ES 挂了）位点就一直不动，
 * 恢复后从断点继续，不丢变更 —— 代价是这期间 ES 越来越旧（延迟），详见 E2 笔记。
 * <p>
 * 默认关闭（测试和没起 Canal 的环境不连）；本地演示用 deploy/e2/start-order-canal.sh 打开。
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
                connector.rollback();   // 从上次 ack 的位点开始
                log.info("Canal 已连接 {}:{} destination={}", host, port, destination);
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
                        // 不 ack，回滚到这一批开头：下次整批重来（同步幂等）
                        log.warn("ES 同步失败，回滚批次 {} 稍后重试", batchId, e);
                        connector.rollback(batchId);
                        Thread.sleep(3000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Canal 连接异常，5 秒后重连 {}:{}", host, port, e);
                sleepQuietly(5000);
            } finally {
                try {
                    connector.disconnect();
                } catch (RuntimeException ignored) {
                    // 断开失败不影响重连
                }
            }
        }
    }

    /**
     * 从一批 binlog 里取出涉及的订单号（去重、保持顺序）。插入 / 更新取变更后的列，删除取变更前的列。
     * 订单头和明细表都有 order_no 列，所以两张表一视同仁。
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
