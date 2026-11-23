package com.merchanthub.common.tenant;

/**
 * 四种业务角色 + 一个内部角色 SYSTEM（C1）。
 * <ul>
 *   <li>PLATFORM_ADMIN：平台管理员，不属于任何商家，能看全平台</li>
 *   <li>MERCHANT_ADMIN：商家管理员，只能看、改自己商家的数据</li>
 *   <li>MERCHANT_STAFF：商家员工，只能看自己商家的数据，不能改（改价等管理接口返回 403）</li>
 *   <li>BUYER：买家，平台级用户，不属于某个商家；数据按 buyer_id 归属过滤</li>
 *   <li>SYSTEM：只在服务内部使用（定时扫描、渠道回调），<b>网关不会给外部 token 签发这个角色</b></li>
 * </ul>
 */
public enum Role {
    PLATFORM_ADMIN,
    MERCHANT_ADMIN,
    MERCHANT_STAFF,
    BUYER,
    SYSTEM;

    /** 商家角色必须带 tenantId；其余角色不带 */
    public boolean isMerchant() {
        return this == MERCHANT_ADMIN || this == MERCHANT_STAFF;
    }

    /** 能出现在用户 token 里的角色（SYSTEM 不行） */
    public boolean isUserRole() {
        return this != SYSTEM;
    }

    /** 解析请求头 / token 里的角色名；不认识返回 null */
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
