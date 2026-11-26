package com.merchanthub.common.tenant;

/**
 * Four business roles + one internal role, SYSTEM.
 * <ul>
 *   <li>PLATFORM_ADMIN: platform administrator, belongs to no merchant, can see the whole platform</li>
 *   <li>MERCHANT_ADMIN: merchant administrator, can only view and modify their own merchant's data</li>
 *   <li>MERCHANT_STAFF: merchant staff, can only view their own merchant's data, not modify it (admin endpoints like price changes return 403)</li>
 *   <li>BUYER: buyer, a platform-level user not tied to any merchant; data is filtered by buyer_id ownership</li>
 *   <li>SYSTEM: used only inside services (scheduled scans, channel callbacks); <b>the gateway never issues this role in external tokens</b></li>
 * </ul>
 */
public enum Role {
    PLATFORM_ADMIN,
    MERCHANT_ADMIN,
    MERCHANT_STAFF,
    BUYER,
    SYSTEM;

    /** Merchant roles must carry a tenantId; other roles must not */
    public boolean isMerchant() {
        return this == MERCHANT_ADMIN || this == MERCHANT_STAFF;
    }

    /** Roles that may appear in a user token (not SYSTEM) */
    public boolean isUserRole() {
        return this != SYSTEM;
    }

    /** Parse a role name from a header / token; returns null if unknown */
    public static Role parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
