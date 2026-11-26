package com.merchanthub.product.catalog.job;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.product.catalog.client.ListingApprovalClient;
import com.merchanthub.product.catalog.client.ListingApprovalClient.ListingResult;
import com.merchanthub.product.catalog.mapper.ProductMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.service.ListingStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Listing approval compensation: for products "under review" longer than older-than, query user-tenant for this round's approval:
 * <ul>
 *   <li>APPROVED / REJECTED → the result message was lost (or went to the DLQ); write back from the query (same CAS as consumption, first one wins)</li>
 *   <li>NOT_FOUND → the original Feign start failed; start again (idempotent per (product, round))</li>
 *   <li>RUNNING → the approver hasn't acted yet; normal, leave it (backfill the instance ID if it's missing)</li>
 *   <li>Query failed → skip, check again next round: "can't look it up" ≠ "no result"</li>
 * </ul>
 * Safe with multiple instances scanning concurrently: write-back is a CAS and starting is idempotent. Scheduled by XXL-Job's listingCompensateJob.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListingAuditCompensator {

    private final ProductMapper productMapper;
    private final ListingApprovalClient approvalClient;
    private final ListingStateService stateService;

    @Value("${merchanthub.product.listing-compensate.older-than:2m}")
    private Duration olderThan;
    @Value("${merchanthub.product.listing-compensate.batch-size:100}")
    private int batchSize;

    /** @return number of products fixed this round (written back + restarted + instance ID backfilled) */
    public int scan() {
        List<Product> stale = TenantContext.callAsSystem(() -> productMapper.selectStaleInReview(olderThan.toSeconds(), batchSize));
        int fixed = 0;
        for (Product p : stale) {
            // Call Feign as the product's merchant: user-tenant takes the merchant from the request header and can only see that merchant's own approvals
            if (TenantContext.callAs(TenantInfo.systemOf(p.getTenantId()), () -> fix(p))) {
                fixed++;
            }
        }
        if (!stale.isEmpty()) {
            log.info("Listing approval compensation done candidates={} fixed={}", stale.size(), fixed);
        }
        return fixed;
    }

    private boolean fix(Product p) {
        int round = p.getAuditRound();
        ListingResult r;
        try {
            BaseResponse<ListingResult> resp = approvalClient.result(p.getId(), round);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("Listing approval compensation: query returned an error, skipping productId={} resp={}", p.getId(), resp);
                return false;
            }
            r = resp.getData();
        } catch (RuntimeException e) {
            log.warn("Listing approval compensation: query failed, skipping productId={} round={}", p.getId(), round, e);
            return false;
        }
        switch (r.state()) {
            case ListingResult.APPROVED, ListingResult.REJECTED -> {
                boolean applied = stateService.applyResult(p.getId(), round, ListingResult.APPROVED.equals(r.state()),
                        r.rejectReason(), r.processInstanceId(), ListingStateService.SOURCE_COMPENSATE);
                if (applied) {
                    log.warn("[Listing approval compensation] result message never arrived, written back from query productId={} round={} state={}", p.getId(), round, r.state());
                }
                return applied;
            }
            case ListingResult.NOT_FOUND -> {
                try {
                    BaseResponse<String> started = approvalClient.start(
                            new ListingApprovalClient.StartListing(p.getId(), round, p.getName()));
                    if (started != null && started.getCode() == 200 && started.getData() != null) {
                        stateService.bindInstance(p.getId(), round, started.getData());
                        log.warn("[Listing approval compensation] approval was never started, restarted productId={} round={} pid={}", p.getId(), round, started.getData());
                        return true;
                    }
                } catch (RuntimeException e) {
                    log.warn("Listing approval compensation: restart failed, will retry next round productId={} round={}", p.getId(), round, e);
                }
                return false;
            }
            default -> {
                return p.getAuditInstanceId() == null && r.processInstanceId() != null
                        && stateService.bindInstance(p.getId(), round, r.processInstanceId());
            }
        }
    }
}
