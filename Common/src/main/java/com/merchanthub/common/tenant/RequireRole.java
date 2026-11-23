package com.merchanthub.common.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限（C1 RBAC）：只有列出的角色能调用，其余返回 HTTP 403；没有身份返回 401。
 * 可以标在类上（整个 Controller）或方法上（方法上的优先）。
 * <p>
 * 只管"这类人能不能调这个接口"；"能不能碰这一条数据"由租户插件（tenant_id）和 buyer_id 归属判断负责，两层缺一不可。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    Role[] value();
}
