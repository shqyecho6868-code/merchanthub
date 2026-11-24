package com.merchanthub.product;

import com.merchanthub.product.catalog.job.ListingAuditCompensator;
import com.merchanthub.product.inventory.job.ReservationReconciler;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * product-inventory 的 XXL-Job 任务入口（E3）。放在根包而不是某个子域：任务入口只是调度适配，
 * 两个子域（catalog / inventory）各自的逻辑仍在各自的类里。
 */
@Component
@RequiredArgsConstructor
public class ProductJobs {

    private final ReservationReconciler reservationReconciler;
    private final ListingAuditCompensator listingCompensator;

    /** 库存预占 ↔ 订单状态对账（原悬空预占扫描，建议每 5 分钟） */
    @XxlJob("reservationReconJob")
    public void reservationRecon() {
        ReservationReconciler.Result r = reservationReconciler.reconcile();
        XxlJobHelper.handleSuccess("释放 " + r.released() + "，确认扣减 " + r.confirmed());
    }

    /** 上架审批补偿（原 @Scheduled 每分钟） */
    @XxlJob("listingCompensateJob")
    public void listingCompensate() {
        XxlJobHelper.handleSuccess("修复 " + listingCompensator.scan() + " 个商品");
    }
}
