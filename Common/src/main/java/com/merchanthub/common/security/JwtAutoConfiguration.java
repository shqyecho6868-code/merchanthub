package com.merchanthub.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@link JwtCodec} is only wired in services that configure merchanthub.jwt.secret (gateway, user-tenant, realtime).
 * Auto-configuration instead of component scanning: the gateway only scans its own package but still needs this Bean.
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.jsonwebtoken.Jwts")
@ConditionalOnProperty(prefix = "merchanthub.jwt", name = "secret")
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtCodec jwtCodec(JwtProperties props) {
        return new JwtCodec(props.getSecret());
    }
}
