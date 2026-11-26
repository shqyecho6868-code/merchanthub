package com.merchanthub.common.tenant;

/**
 * Who the current thread is acting for.
 *
 * @param tenantId merchant ID; <b>when non-null, every business table SQL automatically gets tenant_id = it</b>. Null for buyers, platform admins and cross-tenant system jobs
 * @param userId   operator ID; null for system jobs
 * @param role     role
 */
public record TenantInfo(Long tenantId, Long userId, Role role) {

    public TenantInfo {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (role.isMerchant() && tenantId == null) {
            throw new IllegalArgumentException("商家角色必须带 tenantId: " + role);
        }
    }

    /** Cross-tenant system identity: scheduled scans, channel callbacks. SQL gets no tenant_id, so use it only where crossing tenants is explicitly required */
    public static TenantInfo system() {
        return new TenantInfo(null, null, Role.SYSTEM);
    }

    /** A system action performed as a specific merchant: restoring context in MQ consumers, scan jobs processing records one by one */
    public static TenantInfo systemOf(long tenantId) {
        return new TenantInfo(tenantId, null, Role.SYSTEM);
    }
}
