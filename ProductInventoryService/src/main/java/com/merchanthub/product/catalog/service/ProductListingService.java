package com.merchanthub.product.catalog.service;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.product.catalog.client.ListingApprovalClient;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.vo.ListingSubmitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 提交上架审核（D1）：① 本地事务把商品改成"审核中"、开新一轮 → ② 事务提交后 Feign 让 user-tenant 发起审批 → ③ 回填实例 ID。
 * <p>
 * ② 失败（超时 / user-tenant 挂了）怎么办：<b>不回滚 ①</b>。商品停在"审核中、没有实例 ID"，
 * {@code ListingAuditCompensator} 过一会儿反查，发现没发起过就重新发起（发起接口按 (商品, 轮次) 幂等）。
 * 反过来如果 ② 其实成功了只是响应超时，反查会看到 RUNNING，也不会重复发起。
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

    /** 发起审批并回填实例 ID；失败返回 null（交给补偿），不抛异常 */
    String startRemote(Product p) {
        try {
            BaseResponse<String> resp = approvalClient.start(
                    new ListingApprovalClient.StartListing(p.getId(), p.getAuditRound(), p.getName()));
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("发起上架审批返回异常，等待补偿 productId={} round={} resp={}", p.getId(), p.getAuditRound(), resp);
                return null;
            }
            stateService.bindInstance(p.getId(), p.getAuditRound(), resp.getData());
            return resp.getData();
        } catch (RuntimeException e) {
            log.warn("发起上架审批失败，等待补偿 productId={} round={}", p.getId(), p.getAuditRound(), e);
            return null;
        }
    }
}
