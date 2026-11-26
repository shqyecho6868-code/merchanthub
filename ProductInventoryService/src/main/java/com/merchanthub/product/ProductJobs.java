package com.merchanthub.product;

import com.merchanthub.product.catalog.job.ListingAuditCompensator;
import com.merchanthub.product.inventory.job.ReservationReconciler;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * XXL-Job entry point for product-inventory (E3). It lives in the root package rather than a subdomain: job entry points are just scheduling adapters,
 * and each subdomain's (catalog / inventory) logic stays in its own classes.
 */
@Component
@RequiredArgsConstructor
public class ProductJobs {

    private final ReservationReconciler reservationReconciler;
    private final ListingAuditCompensator listingCompensator;

    /** Inventory reservation ↔ order status reconciliation (formerly the dangling-reservation scan; suggested every 5 minutes) */
    @XxlJob("reservationReconJob")
    public void reservationRecon() {
        ReservationReconciler.Result r = reservationReconciler.reconcile();
        XxlJobHelper.handleSuccess("released " + r.released() + ", confirmed " + r.confirmed());
    }

    /** Listing approval compensation (formerly @Scheduled every minute) */
    @XxlJob("listingCompensateJob")
    public void listingCompensate() {
        XxlJobHelper.handleSuccess("fixed " + listingCompensator.scan() + " products");
    }
}
