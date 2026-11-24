package com.merchanthub.order.search;

import com.alibaba.otter.canal.protocol.CanalEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** E2：从 binlog 行变更里取订单号 —— 只看 orders / order_item 两张表，插入 / 更新取新值，删除取旧值，去重 */
class CanalOrderSyncTest {

    private static CanalEntry.Entry row(String table, CanalEntry.EventType type, long orderNo) {
        CanalEntry.Column col = CanalEntry.Column.newBuilder().setName("order_no").setValue(String.valueOf(orderNo)).build();
        CanalEntry.RowData.Builder rd = CanalEntry.RowData.newBuilder();
        if (type == CanalEntry.EventType.DELETE) {
            rd.addBeforeColumns(col);
        } else {
            rd.addAfterColumns(col);
        }
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder().setEventType(type).addRowDatas(rd).build();
        return CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("mh_order").setTableName(table))
                .setStoreValue(change.toByteString())
                .build();
    }

    @Test
    void extractsDistinctOrderNos() throws Exception {
        CanalEntry.Entry txBegin = CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.TRANSACTIONBEGIN)
                .setHeader(CanalEntry.Header.newBuilder().setTableName("")).build();
        List<CanalEntry.Entry> entries = List.of(
                txBegin,
                row("orders", CanalEntry.EventType.INSERT, 123),
                row("order_item", CanalEntry.EventType.INSERT, 123),
                row("orders", CanalEntry.EventType.UPDATE, 123),
                row("order_outbox", CanalEntry.EventType.INSERT, 999),   // 不关心的表
                row("orders", CanalEntry.EventType.DELETE, 456));

        assertThat(CanalOrderSync.orderNosOf(entries)).containsExactly(123L, 456L);
    }
}
