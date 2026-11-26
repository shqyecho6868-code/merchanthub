package com.merchanthub.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** JWT settings (D1). The secret comes only from config / environment variables, with no default in code: forgetting it fails startup instead of silently using a well-known secret */
@Data
@ConfigurationProperties(prefix = "merchanthub.jwt")
public class JwtProperties {

    /** Standard Base64 (padded with =), at least 32 bytes after decoding */
    private String secret;
    /** Access-Token lifetime; once expired, exchange the Refresh-Token for a new one */
    private Duration accessTtl = Duration.ofHours(2);
    private Duration refreshTtl = Duration.ofDays(7);
}
