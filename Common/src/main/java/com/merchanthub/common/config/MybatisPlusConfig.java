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
 * MyBatis-Plus 统一配置
 *
 * 说明：此配置类位于 Common 模块，通过 scanBasePackages 被所有微服务自动扫描加载。
 * MyBatis-Plus 在 Common 中是 optional 依赖，不用 MyBatis 的服务（如 realtime 用 JdbcTemplate、网关不连库）classpath 上没有它，
 * 所以用 @ConditionalOnClass 按需装配；用字符串类名，类不存在时也能安全判断。
 */
@Configuration
@ConditionalOnClass(name = "com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor")
public class MybatisPlusConfig {

    /**
     * 分页插件配置
     *
     * 功能：
     * - 自动计算分页 total 值
     * - 自动添加 LIMIT 子句
     * - 防止全表更新删除
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(Environment env) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户插件（C1）：业务表自动拼 tenant_id 条件，规则见 TenantSqlHandler。
        // 必须加在分页插件之前：先改写成带 tenant_id 的 SQL，分页插件再基于改写后的 SQL 生成 count 和 LIMIT，
        // 反过来 count 语句会漏掉租户条件（MyBatis-Plus 官方文档要求的顺序）。
        // 需要时可在服务配置里关掉（merchanthub.tenant.enabled=false）；旧 IM 服务已全部改造或删除，目前都开着
        if (env.getProperty("merchanthub.tenant.enabled", Boolean.class, true)) {
            Set<String> ignore = Arrays.stream(env.getProperty("merchanthub.tenant.ignore-tables", "").split(","))
                    .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantSqlHandler(ignore)));
        }

        // 分页插件（指定数据库类型为 MySQL）
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 设置最大单页限制数量，-1 不受限制
        paginationInterceptor.setMaxLimit(500L);
        // 溢出总页数后是否进行处理
        paginationInterceptor.setOverflow(false);

        interceptor.addInnerInterceptor(paginationInterceptor);

        return interceptor;
    }
}