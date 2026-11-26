package com.merchanthub.common.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint-level permission (C1 RBAC): only the listed roles may call it, others get HTTP 403; no identity gets 401.
 * Can be placed on a class (the whole Controller) or a method (the method wins).
 * <p>
 * It only answers "can this kind of user call this endpoint"; "can they touch this row" is handled by the tenant plugin (tenant_id) and buyer_id ownership checks. Both layers are required.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    Role[] value();
}
