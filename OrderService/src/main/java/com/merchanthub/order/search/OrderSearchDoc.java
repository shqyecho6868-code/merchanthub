package com.merchanthub.order.search;

import java.util.List;

/**
 * Order document in ES (index orders, document id = orderNo). A <b>read-only replica</b> of MySQL: used only for "search by keyword",
 * never for any transactional decision -- sync lags by seconds, and MySQL is the source of truth for status.
 *
 * @param itemNames all product names in the order; text type, the field searched by terms
 * @param createdAt epoch millis (UTC), avoiding time-zone format issues
 */
public record OrderSearchDoc(String orderNo, long tenantId, long buyerId, int status, long payAmount,
                             long createdAt, List<String> itemNames, List<String> skuSpecs) {
}
