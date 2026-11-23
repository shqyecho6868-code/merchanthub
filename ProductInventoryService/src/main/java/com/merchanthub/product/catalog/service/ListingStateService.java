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
 * 商品上架审批的本地状态变更（D1）。每个方法一个短事务：状态 CAS + 状态流水一起提交。
 * <b>不在事务里调远程</b>：Feign 发起审批放在 {@link ProductListingService} 里、事务提交之后，
 * 否则 user-tenant 慢 3 秒，这行商品的行锁就被占 3 秒。
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

    /** 草稿 / 已驳回 → 审核中，开新一轮。返回提交后的商品（带新的 audit_round） */
    @Transactional
    public Product submit(long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            // 租户插件拼了 tenant_id：别的商家的商品在这里就是"不存在"
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        if (!ProductStatus.submittable(p.getStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_SUBMITTABLE);
        }
        // CAS 带上刚读到的旧状态：两个员工同时点"提交"，只有一个能成功，另一个不会再开出第二轮
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
     * 回写审批结果（消息消费和补偿扫描共用这一个入口）。返回 false = 重复 / 过期的结果，什么都没改。
     * 只有 CAS 成功才写流水：重复消息不会多出一条"已上架"流水。
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
