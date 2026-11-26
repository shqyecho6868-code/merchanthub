package com.merchanthub.product.catalog.model.enums;

/**
 * Product status (product.status). Transitions related to listing approval:
 * <pre>
 *   0 draft ──submit──▶ 1 under review ──approve──▶ 2 listed (buyers can order: order only accepts products with status = 2)
 *   4 rejected ─submit─▶ 1 under review ──reject──▶ 4 rejected
 * </pre>
 * Only 1 can become 2 / 4, and only within the same round (audit_round); every other direction is a no-op. 3 delisted is reserved for a future delisting feature.
 */
public final class ProductStatus {

    public static final int DRAFT = 0;
    public static final int IN_REVIEW = 1;
    public static final int LISTED = 2;
    public static final int DELISTED = 3;
    public static final int REJECTED = 4;

    private ProductStatus() {
    }

    public static boolean submittable(int status) {
        return status == DRAFT || status == REJECTED;
    }
}
