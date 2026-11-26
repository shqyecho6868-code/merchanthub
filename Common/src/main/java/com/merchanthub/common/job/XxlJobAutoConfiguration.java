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
 * XXL-Job executor auto-configuration (E3): any service that declares the xxl-job-core dependency and turns on merchanthub.job.xxl.enabled
 * registers as an executor, and its @XxlJob methods become schedulable jobs.
 * <p>
 * Why job methods are separate from the executor switch: job classes (e.g. OrderJobs) are always plain Beans that tests call directly;
 * only the executor (embedded HTTP server + registration with the admin) is behind the switch. Tests don't need a running admin.
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
        log.info("XXL-Job executor appname={} port={} admin={}", name, props.getPort(), props.getAdminAddresses());
        return executor;
    }
}
