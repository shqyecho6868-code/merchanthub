package com.merchanthub.user.approval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.user.approval.flow.FlowVars;
import com.merchanthub.user.approval.mapper.ListingApprovalMapper;
import com.merchanthub.user.approval.model.entity.ListingApproval;
import com.merchanthub.user.approval.model.vo.ListingResultVO;
import com.merchanthub.user.approval.mq.ListingResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Starting product listing approvals and querying results (internal endpoints called by product via Feign).
 * The merchant ID always comes from the identity headers (TenantContext), never the request body: a product request from merchant 7 can't start an approval for merchant 8.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingApprovalService {

    private final ListingApprovalMapper approvalMapper;
    private final RuntimeService runtimeService;

    /**
     * Start (idempotent): if the same (merchant, product, round) was already started, return the existing instance.
     * product's Feign timeout retries and compensation scans both call this repeatedly -- a timeout doesn't mean the start failed.
     * <p>
     * Two concurrent duplicate calls both find nothing -> both start an instance -> the unique key conflicts when inserting the approval record, and the later whole transaction rolls back (including the instance it started);
     * the caller (Controller) catches it and queries again to get the instance ID of the one that succeeded first.
     */
    @Transactional
    public String start(long productId, int round, String productName) {
        TenantInfo me = TenantContext.get();
        long tenantId = TenantContext.requireTenantId();
        ListingApproval existing = find(tenantId, productId, round);
        if (existing != null) {
            log.info("Listing approval already started, returning it productId={} round={} pid={}", productId, round, existing.getProcessInstanceId());
            return existing.getProcessInstanceId();
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put(FlowVars.TENANT_ID, tenantId);
        vars.put(FlowVars.PRODUCT_ID, productId);
        vars.put(FlowVars.ROUND, round);
        vars.put(FlowVars.PRODUCT_NAME, productName);
        vars.put(FlowVars.INITIATOR, me.userId());
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(FlowVars.PROCESS_LISTING,
                "listing:" + tenantId + ":" + productId + ":" + round, vars);

        ListingApproval row = new ListingApproval();
        row.setTenantId(tenantId);
        row.setProductId(productId);
        row.setAuditRound(round);
        row.setProductName(productName);
        row.setInitiatorUserId(me.userId());
        row.setProcessInstanceId(pi.getId());
        row.setStatus(ListingApproval.RUNNING);
        approvalMapper.insert(row);
        log.info("Listing approval started tenantId={} productId={} round={} pid={}", tenantId, productId, round, pi.getId());
        return pi.getId();
    }

    /** Read-only query, a separate method outside the transaction: after start rolls back on conflict, the Controller uses it to find the one that succeeded first */
    public String existingInstance(long productId, int round) {
        ListingApproval row = find(TenantContext.requireTenantId(), productId, round);
        return row == null ? null : row.getProcessInstanceId();
    }

    /** Lookup for product's compensation scan: when the message is lost / never written back, this is the source of truth */
    public ListingResultVO result(long productId, int round) {
        ListingApproval row = find(TenantContext.requireTenantId(), productId, round);
        if (row == null) {
            return new ListingResultVO(ListingResultVO.NOT_FOUND, null, null);
        }
        String state = switch (row.getStatus()) {
            case ListingApproval.APPROVED -> ListingResultEvent.APPROVED;
            case ListingApproval.REJECTED -> ListingResultEvent.REJECTED;
            default -> ListingResultVO.RUNNING;
        };
        return new ListingResultVO(state, row.getProcessInstanceId(), row.getRejectReason());
    }

    private ListingApproval find(long tenantId, long productId, int round) {
        // The tenant plugin appends tenant_id = current merchant again anyway; it's written explicitly so readers see the unique key at a glance
        return approvalMapper.selectOne(new LambdaQueryWrapper<ListingApproval>()
                .eq(ListingApproval::getTenantId, tenantId)
                .eq(ListingApproval::getProductId, productId)
                .eq(ListingApproval::getAuditRound, round));
    }
}
