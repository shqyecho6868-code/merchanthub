package com.merchanthub.common.recon;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 有数据源的服务自动得到 {@link ReconAlarm}（表 recon_exception 由各服务自己的 Flyway 脚本建） */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean(JdbcTemplate.class)
public class ReconAlarmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReconAlarm reconAlarm(JdbcTemplate jdbc) {
        return new ReconAlarm(jdbc);
    }
}
