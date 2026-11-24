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
 * 商家按关键词搜订单（E2，只演示）：商品名 / 规格分词匹配，或者直接输订单号。
 * <p>
 * <b>租户隔离</b>：tenantId 作为 filter 条件<b>强制</b>加在每次查询上（由调用方从网关注入的身份取，不接受前端传）。
 * MyBatis 的租户插件管不到 ES，这一步漏了就是跨商家数据泄露。
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
                            // 订单号精确匹配，或商品名 / 规格分词匹配（AND：每个词都要出现，减少无关结果）
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
            // ES 是查询副本，挂了不影响交易，只让搜索降级报错
            log.warn("订单搜索失败 tenantId={} keyword={}", tenantId, kw, e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "订单搜索暂不可用");
        }
    }
}
