package com.merchanthub.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.merchanthub.common.tenant.mybatis.TenantSqlHandler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared MyBatis-Plus configuration
 *
 * Note: this config class lives in Common and is picked up by every microservice via scanBasePackages.
 * MyBatis-Plus is an optional dependency of Common; services that don't use MyBatis (e.g. realtime uses JdbcTemplate, the gateway has no DB) don't have it on the classpath,
 * so it is wired conditionally with @ConditionalOnClass; using the class name as a string keeps the check safe when the class is absent.
 */
@Configuration
@ConditionalOnClass(name = "com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor")
public class MybatisPlusConfig {

    /**
     * Pagination plugin configuration
     *
     * Features:
     * - computes the pagination total automatically
     * - appends the LIMIT clause automatically
     * - guards against full-table update/delete
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(Environment env) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // Tenant plugin (C1): appends a tenant_id condition on business tables; rules live in TenantSqlHandler.
        // Must come before the pagination plugin: rewrite the SQL with tenant_id first, then pagination builds count and LIMIT from the rewritten SQL;
        // the other way round the count query would miss the tenant condition (this order is required by the MyBatis-Plus docs).
        // Can be turned off per service (merchanthub.tenant.enabled=false); the old IM services have all been migrated or removed, so it is on everywhere today
        if (env.getProperty("merchanthub.tenant.enabled", Boolean.class, true)) {
            Set<String> ignore = Arrays.stream(env.getProperty("merchanthub.tenant.ignore-tables", "").split(","))
                    .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantSqlHandler(ignore)));
        }

        // Pagination plugin (database type: MySQL)
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // Max page size; -1 means unlimited
        paginationInterceptor.setMaxLimit(500L);
        // Whether to handle page numbers beyond the last page
        paginationInterceptor.setOverflow(false);

        interceptor.addInnerInterceptor(paginationInterceptor);

        return interceptor;
    }
}