package com.merchanthub.common.tenant;

/**
 * 当前线程代表"谁"在操作。
 *
 * @param tenantId 商家 ID；<b>非 null 时所有业务表 SQL 都会自动拼 tenant_id = 它</b>。买家、平台管理员、跨租户的系统任务为 null
 * @param userId   操作人 ID；系统任务为 null
 * @param role     角色
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

    /** 跨租户的系统身份：定时扫描、渠道回调。SQL 不拼 tenant_id，所以只能用在明确需要跨租户的地方 */
    public static TenantInfo system() {
        return new TenantInfo(null, null, Role.SYSTEM);
    }

    /** 以某个商家的身份执行的系统动作：MQ 消费恢复上下文、扫描任务按记录逐个处理 */
    public static TenantInfo systemOf(long tenantId) {
        return new TenantInfo(tenantId, null, Role.SYSTEM);
    }
}
