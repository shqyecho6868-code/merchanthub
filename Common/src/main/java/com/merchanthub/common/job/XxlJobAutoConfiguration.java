package com.merchanthub.common.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * XXL-Job 执行器自动装配（E3）：服务只要声明了 xxl-job-core 依赖、并打开 merchanthub.job.xxl.enabled，
 * 就会注册成执行器，@XxlJob 方法自动成为可调度的任务。
 * <p>
 * 为什么任务方法和"执行器开关"分开：任务类（如 OrderJobs）始终是普通 Bean，测试里直接调用；
 * 只有执行器（内嵌 HTTP 服务 + 向调度中心注册）受开关控制。测试不需要起调度中心。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(XxlJobSpringExecutor.class)
@ConditionalOnProperty(name = "merchanthub.job.xxl.enabled", havingValue = "true")
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobAutoConfiguration {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties props,
                                               @Value("${spring.application.name:app}") String appName) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        String name = props.getAppname() == null || props.getAppname().isBlank() ? appName : props.getAppname();
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAccessToken(props.getAccessToken());
        executor.setAppname(name);
        executor.setPort(props.getPort());
        if (props.getAddress() != null && !props.getAddress().isBlank()) {
            executor.setAddress(props.getAddress());
        }
        executor.setLogPath(props.getLogPath() + "/" + name);
        executor.setLogRetentionDays(props.getLogRetentionDays());
        log.info("XXL-Job 执行器 appname={} port={} admin={}", name, props.getPort(), props.getAdminAddresses());
        return executor;
    }
}
