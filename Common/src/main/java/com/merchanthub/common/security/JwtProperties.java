package com.merchanthub.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** JWT 配置（D1）。密钥只从配置 / 环境变量来，代码里没有默认值：忘了配就启动失败，而不是悄悄用一个大家都知道的密钥 */
@Data
@ConfigurationProperties(prefix = "merchanthub.jwt")
public class JwtProperties {

    /** 标准 Base64（补齐 =），解码后至少 32 字节 */
    private String secret;
    /** Access-Token 有效期；过期后用 Refresh-Token 换新 */
    private Duration accessTtl = Duration.ofHours(2);
    private Duration refreshTtl = Duration.ofDays(7);
}
