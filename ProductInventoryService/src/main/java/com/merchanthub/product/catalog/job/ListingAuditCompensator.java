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
 * 上架审批补偿（D1）："审核中"超过 older-than 的商品，去 user-tenant 反查这一轮的审批单：
 * <ul>
 *   <li>APPROVED / REJECTED → 结果消息丢了（或进了死信），按反查结果回写（和消费走同一个 CAS，先到的生效）</li>
 *   <li>NOT_FOUND → 当初 Feign 发起失败，重新发起（按 (商品, 轮次) 幂等）</li>
 *   <li>RUNNING → 审批人还没处理，正常情况，不动（实例 ID 没回填的顺手补上）</li>
 *   <li>反查失败 → 跳过，下一轮再查：查不到 ≠ 没结果</li>
 * </ul>
 * 多实例同时扫描安全：回写是 CAS，发起是幂等的。E3 起由 XXL-Job 的 listingCompensateJob 调度（原 @Scheduled）。
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

    /** @return 本轮修复（回写 + 重新发起 + 补实例 ID）的商品数 */
    public int scan() {
        List<Product> stale = TenantContext.callAsSystem(() -> productMapper.selectStaleInReview(olderThan.toSeconds(), batchSize));
        int fixed = 0;
        for (Product p : stale) {
            // 按商品所属商家调 Feign：user-tenant 从请求头取商家，只能查到这个商家自己的审批单
            if (TenantContext.callAs(TenantInfo.systemOf(p.getTenantId()), () -> fix(p))) {
                fixed++;
            }
        }
        if (!stale.isEmpty()) {
            log.info("上架审批补偿完成 候选={} 修复={}", stale.size(), fixed);
        }
        return fixed;
    }

    private boolean fix(Product p) {
        int round = p.getAuditRound();
        ListingResult r;
        try {
            BaseResponse<ListingResult> resp = approvalClient.result(p.getId(), round);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("上架审批补偿：反查返回异常，跳过 productId={} resp={}", p.getId(), resp);
                return false;
            }
            r = resp.getData();
        } catch (RuntimeException e) {
            log.warn("上架审批补偿：反查失败，跳过 productId={} round={}", p.getId(), round, e);
            return false;
        }
        switch (r.state()) {
            case ListingResult.APPROVED, ListingResult.REJECTED -> {
                boolean applied = stateService.applyResult(p.getId(), round, ListingResult.APPROVED.equals(r.state()),
                        r.rejectReason(), r.processInstanceId(), ListingStateService.SOURCE_COMPENSATE);
                if (applied) {
                    log.warn("【上架审批补偿】结果消息未到，已按反查回写 productId={} round={} state={}", p.getId(), round, r.state());
                }
                return applied;
            }
            case ListingResult.NOT_FOUND -> {
                try {
                    BaseResponse<String> started = approvalClient.start(
                            new ListingApprovalClient.StartListing(p.getId(), round, p.getName()));
                    if (started != null && started.getCode() == 200 && started.getData() != null) {
                        stateService.bindInstance(p.getId(), round, started.getData());
                        log.warn("【上架审批补偿】审批未发起，已重新发起 productId={} round={} pid={}", p.getId(), round, started.getData());
                        return true;
                    }
                } catch (RuntimeException e) {
                    log.warn("上架审批补偿：重新发起失败，下一轮再试 productId={} round={}", p.getId(), round, e);
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
