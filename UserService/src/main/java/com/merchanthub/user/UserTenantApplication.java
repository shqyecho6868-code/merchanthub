package com.merchanthub.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-tenant service (reworked in D1 from the old IM UserService). Two subdomains:
 * <ul>
 *   <li>account: accounts, tenants, login (issues JWTs carrying identity)</li>
 *   <li>approval: home of the Flowable engine -- merchant onboarding approval (closed loop locally) and product listing approval (started by product via Feign, result written back via MQ)</li>
 * </ul>
 * MapperScan only scans our own packages: Flowable has its own MyBatis setup and the ACT_* tables aren't managed by MyBatis-Plus (so the tenant plugin never rewrites them).
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.user", "com.merchanthub.common"})
@MapperScan({"com.merchanthub.user.account.mapper", "com.merchanthub.user.approval.mapper"})
public class UserTenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserTenantApplication.class, args);
    }
}
