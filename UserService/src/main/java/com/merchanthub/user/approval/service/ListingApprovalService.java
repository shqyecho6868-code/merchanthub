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
 * 商品上架审批的发起与结果查询（product 经 Feign 调用的内部接口）。
 * 商家 ID 一律取自请求头身份（TenantContext），不信请求体：商家 7 的 product 请求发起不了商家 8 的审批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingApprovalService {

    private final ListingApprovalMapper approvalMapper;
    private final RuntimeService runtimeService;

    /**
     * 发起（幂等）：同一个 (商家, 商品, 轮次) 已经发起过就直接返回原来的实例。
     * product 的 Feign 超时重试、补偿扫描都会重复调这里 —— 超时不代表没发起成功。
     * <p>
     * 并发的两次重复调用都查不到 → 都启动实例 → 插审批单时唯一键冲突，后一个整个事务回滚（连同它启动的实例），
     * 调用方（Controller）捕获后再查一次，拿到先成功那个的实例 ID。
     */
    @Transactional
    public String start(long productId, int round, String productName) {
        TenantInfo me = TenantContext.get();
        long tenantId = TenantContext.requireTenantId();
        ListingApproval existing = find(tenantId, productId, round);
        if (existing != null) {
            log.info("上架审批已发起过，直接返回 productId={} round={} pid={}", productId, round, existing.getProcessInstanceId());
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
        log.info("发起上架审批 tenantId={} productId={} round={} pid={}", tenantId, productId, round, pi.getId());
        return pi.getId();
    }

    /** 只读查询，单独一个事务外的方法：start 冲突回滚后，Controller 用它查先成功的那一个 */
    public String existingInstance(long productId, int round) {
        ListingApproval row = find(TenantContext.requireTenantId(), productId, round);
        return row == null ? null : row.getProcessInstanceId();
    }

    /** product 补偿扫描反查：消息丢了 / 一直没回写时，以这里为准 */
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
        // 租户插件还会再拼一次 tenant_id = 当前商家；这里显式写出来是为了读代码的人一眼看懂唯一键
        return approvalMapper.selectOne(new LambdaQueryWrapper<ListingApproval>()
                .eq(ListingApproval::getTenantId, tenantId)
                .eq(ListingApproval::getProductId, productId)
                .eq(ListingApproval::getAuditRound, round));
    }
}
