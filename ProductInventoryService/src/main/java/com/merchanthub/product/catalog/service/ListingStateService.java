package com.merchanthub.product.catalog.service;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.catalog.mapper.ProductMapper;
import com.merchanthub.product.catalog.mapper.ProductStatusLogMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.entity.ProductStatusLog;
import com.merchanthub.product.catalog.model.enums.ProductStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local status changes for product listing approval (D1). One short transaction per method: status CAS + status history committed together.
 * <b>No remote calls inside the transaction</b>: the Feign call that starts the approval lives in {@link ProductListingService}, after the commit;
 * otherwise if user-tenant is 3 seconds slow, this product row's lock is held for 3 seconds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingStateService {

    public static final String SOURCE_API = "API";
    public static final String SOURCE_MQ = "MQ";
    public static final String SOURCE_COMPENSATE = "COMPENSATE";

    private final ProductMapper productMapper;
    private final ProductStatusLogMapper logMapper;

    /** Draft / rejected → under review, starting a new round. Returns the product after submission (with the new audit_round) */
    @Transactional
    public Product submit(long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            // The tenant plugin appended tenant_id: another merchant's product simply "doesn't exist" here
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        if (!ProductStatus.submittable(p.getStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_SUBMITTABLE);
        }
        // CAS with the old status just read: if two staff click "submit" at once, only one succeeds and the other can't open a second round
        if (productMapper.markSubmitted(productId, p.getStatus()) != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_SUBMITTABLE);
        }
        Product after = productMapper.selectById(productId);
        writeLog(after, p.getStatus(), ProductStatus.IN_REVIEW, "SUBMIT_LISTING", SOURCE_API, null);
        log.info("商品提交上架审核 productId={} round={} tenantId={}", productId, after.getAuditRound(), after.getTenantId());
        return after;
    }

    public boolean bindInstance(long productId, int round, String processInstanceId) {
        return productMapper.bindInstance(productId, round, processInstanceId) == 1;
    }

    /**
     * Write back the approval result (the single entry point shared by message consumption and the compensation scan). Returns false = duplicate / stale result, nothing changed.
     * History is written only if the CAS succeeds: a duplicate message won't add an extra "listed" history row.
     */
    @Transactional
    public boolean applyResult(long productId, int round, boolean approved, String reason, String processInstanceId,
                               String source) {
        int to = approved ? ProductStatus.LISTED : ProductStatus.REJECTED;
        if (productMapper.applyAuditResult(productId, round, to, approved ? null : reason, processInstanceId) != 1) {
            log.info("审批结果未生效（重复 / 过期 / 非审核中）productId={} round={} approved={} source={}",
                    productId, round, approved, source);
            return false;
        }
        Product p = productMapper.selectById(productId);
        writeLog(p, ProductStatus.IN_REVIEW, to, approved ? "LISTING_APPROVED" : "LISTING_REJECTED", source, reason);
        log.info("审批结果已回写 productId={} round={} status={} source={} pid={}", productId, round, to, source, processInstanceId);
        return true;
    }

    private void writeLog(Product p, int from, int to, String event, String source, String remark) {
        ProductStatusLog l = new ProductStatusLog();
        l.setTenantId(p.getTenantId());
        l.setProductId(p.getId());
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setEvent(event);
        l.setAuditRound(p.getAuditRound());
        l.setSource(source);
        l.setRemark(remark);
        logMapper.insert(l);
    }
}
