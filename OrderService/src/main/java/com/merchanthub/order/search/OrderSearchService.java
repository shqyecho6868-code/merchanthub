package com.merchanthub.order.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Merchants search orders by keyword (E2, demo only): analyzed match on product name / spec, or the exact order number.
 * <p>
 * <b>Tenant isolation</b>: tenantId is <b>always</b> added as a filter on every query (the caller takes it from the gateway-injected identity, never from the frontend).
 * The MyBatis tenant plugin doesn't cover ES; missing this step means cross-merchant data leakage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSearchService {

    private final ElasticsearchClient es;

    public List<OrderSearchDoc> search(long tenantId, String keyword, int page, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        int from = Math.max(page - 1, 0) * size;
        try {
            SearchResponse<OrderSearchDoc> resp = es.search(s -> s.index(OrderSearchIndexer.INDEX)
                    .query(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field("tenantId").value(tenantId)));
                        if (!kw.isEmpty()) {
                            // Exact order-number match, or analyzed match on product name / spec (AND: every term must appear, reducing irrelevant hits)
                            b.should(sh -> sh.term(t -> t.field("orderNo").value(kw)));
                            b.should(sh -> sh.multiMatch(m -> m.query(kw).fields("itemNames", "skuSpecs").operator(Operator.And)));
                            b.minimumShouldMatch("1");
                        }
                        return b;
                    }))
                    .sort(o -> o.field(f -> f.field("createdAt").order(SortOrder.Desc)))
                    .from(from).size(size), OrderSearchDoc.class);
            return resp.hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList();
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            // ES is a query replica; if it's down transactions are unaffected, only search degrades with an error
            log.warn("订单搜索失败 tenantId={} keyword={}", tenantId, kw, e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "订单搜索暂不可用");
        }
    }
}
