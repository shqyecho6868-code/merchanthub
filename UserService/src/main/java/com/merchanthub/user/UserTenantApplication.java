package com.merchanthub.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-tenant 服务（D1 从旧 IM 的 UserService 改造）。两个子域：
 * <ul>
 *   <li>account：账号、租户、登录（签发带身份的 JWT）</li>
 *   <li>approval：Flowable 引擎所在地 —— 商家入驻审批（本地闭环）、商品上架审批（product 经 Feign 发起，结果经 MQ 回写）</li>
 * </ul>
 * MapperScan 只扫自己的包：Flowable 有自己的一套 MyBatis，ACT_* 表不归 MyBatis-Plus 管（也就不会被租户插件改写）。
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.user", "com.merchanthub.common"})
@MapperScan({"com.merchanthub.user.account.mapper", "com.merchanthub.user.approval.mapper"})
public class UserTenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserTenantApplication.class, args);
    }
}
