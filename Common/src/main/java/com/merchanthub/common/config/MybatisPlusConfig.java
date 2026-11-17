package com.merchanthub.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 统一配置
 *
 * 说明：此配置类位于 Common 模块，通过 scanBasePackages 被所有微服务自动扫描加载。
 * MyBatis-Plus 在 Common 中是 optional 依赖，不连数据库的服务（如 realtime）classpath 上没有它，
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
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

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