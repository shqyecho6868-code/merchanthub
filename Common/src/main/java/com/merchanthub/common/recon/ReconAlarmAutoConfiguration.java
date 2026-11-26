package com.merchanthub.common.recon;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Services with a data source get a {@link ReconAlarm} automatically (the recon_exception table is created by each service's own Flyway scripts) */
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
