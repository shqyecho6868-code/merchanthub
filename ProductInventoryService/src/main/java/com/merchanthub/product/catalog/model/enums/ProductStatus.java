package com.merchanthub.product.catalog.model.enums;

/**
 * 商品状态（product.status）。上架审批相关的流转（D1）：
 * <pre>
 *   0 草稿 ──提交──▶ 1 审核中 ──通过──▶ 2 已上架（买家可下单：order 只接受 status = 2 的商品）
 *   4 已驳回 ─提交─▶ 1 审核中 ──驳回──▶ 4 已驳回
 * </pre>
 * 只有 1 能变成 2 / 4，且必须是同一轮（audit_round）；其他方向一律不改。3 已下架留给以后的下架功能。
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
