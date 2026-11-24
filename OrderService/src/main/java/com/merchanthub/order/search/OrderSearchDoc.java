package com.merchanthub.order.search;

import java.util.List;

/**
 * ES 里的订单文档（索引 orders，文档 id = orderNo）。是 MySQL 的<b>只读副本</b>：只用于"按关键词搜"，
 * 不参与任何交易判断 —— 同步有秒级延迟，状态以 MySQL 为准。
 *
 * @param itemNames 订单里所有商品名，text 类型，按词搜索的字段
 * @param createdAt epoch 毫秒（UTC），避免时区格式问题
 */
public record OrderSearchDoc(String orderNo, long tenantId, long buyerId, int status, long payAmount,
                             long createdAt, List<String> itemNames, List<String> skuSpecs) {
}
