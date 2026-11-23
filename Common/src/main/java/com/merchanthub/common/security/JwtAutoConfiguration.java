package com.merchanthub.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 配了 merchanthub.jwt.secret 的服务（网关、user-tenant、realtime）才装配 {@link JwtCodec}。
 * 用自动装配而不是组件扫描：网关只扫描自己的包，也要能拿到这个 Bean。
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
