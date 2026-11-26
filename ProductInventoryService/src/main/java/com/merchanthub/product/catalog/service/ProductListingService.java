package com.merchanthub.product.catalog.service;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.product.catalog.client.ListingApprovalClient;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.vo.ListingSubmitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Submit for listing review (D1): ① a local transaction moves the product to "under review" and opens a new round → ② after commit, Feign asks user-tenant to start the approval → ③ backfill the instance ID.
 * <p>
 * What if ② fails (timeout / user-tenant down): <b>don't roll back ①</b>. The product stays "under review, no instance ID", and
 * {@code ListingAuditCompensator} queries it a bit later and restarts it if it was never started (the start API is idempotent per (product, round)).
 * Conversely, if ② actually succeeded and only the response timed out, the query sees RUNNING and doesn't start it again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductListingService {

    private final ListingStateService stateService;
    private final ListingApprovalClient approvalClient;

    public ListingSubmitVO submit(long productId) {
        Product p = stateService.submit(productId);
        String pid = startRemote(p);
        return new ListingSubmitVO(p.getId(), p.getAuditRound(), p.getStatus(), pid);
    }

    /** Start the approval and backfill the instance ID; returns null on failure (left to compensation) instead of throwing */
    String startRemote(Product p) {
        try {
            BaseResponse<String> resp = approvalClient.start(
                    new ListingApprovalClient.StartListing(p.getId(), p.getAuditRound(), p.getName()));
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("Starting listing approval returned an error, awaiting compensation productId={} round={} resp={}", p.getId(), p.getAuditRound(), resp);
                return null;
            }
            stateService.bindInstance(p.getId(), p.getAuditRound(), resp.getData());
            return resp.getData();
        } catch (RuntimeException e) {
            log.warn("Starting listing approval failed, awaiting compensation productId={} round={}", p.getId(), p.getAuditRound(), e);
            return null;
        }
    }
}
